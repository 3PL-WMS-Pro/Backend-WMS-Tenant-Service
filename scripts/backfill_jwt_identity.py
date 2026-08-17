#!/usr/bin/env python3
"""
Phase M9 data repair — replace FreighAi opaque user ids with emails in the
email-typed audit fields of `wms_pro_tenant_199`.

Between 2026-05-05 (deploy of `61821c4`, the JwtTokenExtractor secret fix) and
2026-08-14, `JwtTokenExtractor.extractUsername()` returned the JWT `sub` claim —
FreighAi's opaque user id — instead of the `email` claim. Every consumer names
that value `userEmail` and persists it into a field that is contractually an
email, so `UserService.fetchUserFullNames` resolves it against FreighAi's
`/users/batch-by-emails`, misses, and renders a blank name in both clients.

Exactly two ids are responsible (see MOBILE-APP-FREIGHAI-MIGRATION.md §2.4):

    user_d604055f03e6  ->  gautam@infinitylogisticsme.com
    user_d51ef8bddcdd  ->  cibin@infinitylogisticsme.com

Both are resolved live against FreighAi's `users` collection before anything is
written; a missing id, or one whose stored email differs from the expected value,
aborts the run.

`receiving_records.statusHistory` is an array of subdocuments and a single record
can carry several contaminated entries, so it is repaired with a positional
`$[elem]` update plus arrayFilters — every matching element in every matching
document, in one op, without rewriting whole documents.

Counts in the doc above are a 2026-08-14 snapshot. This script never trusts them:
it re-counts live, prints a per-field/per-id table, and flags drift.

Idempotent — every update is filtered on the contaminated value itself, so a
second run matches nothing and reports 0 changes.

Default: dry-run (prints what would happen, writes nothing). Pass --apply to
actually write to the WMS DB.

**Run only after M1 is deployed.** If contaminated rows are still being written
behind the script, the post-apply re-verification will be non-zero and this
script exits 1 to tell you so.

Usage:
    python3 backfill_jwt_identity.py                  # dry-run
    python3 backfill_jwt_identity.py --verify-only    # counts only, no FreighAi call
    python3 backfill_jwt_identity.py --apply          # live write
"""

from __future__ import annotations

import argparse
import os
import sys
import traceback

from pymongo import MongoClient

WMS_URI_DEFAULT = "mongodb://flybizdigi:FlyBizDigi%40123@cloud.leadtorev.com:27170/?authSource=admin"
FREIGHAI_URI_DEFAULT = "mongodb://admin:Fr31ghA1M0ng0Pr0d2026X9K7v@api.freighai.com:27179/?authSource=admin"

WMS_TENANT_DB = "wms_pro_tenant_199"
WMS_CENTRAL_DB = "wms_pro_tenants"
FREIGHAI_TENANT_DB = "freighai_testco"

WMS_TENANT_ID = 199
FREIGHAI_USERS_COLLECTION = "users"

# FreighAi's users doc stores the address in `email` (verified via
# FreighAiAuthUserInfo). The alternates are probed only so a shape change
# surfaces as a clear message instead of a bogus "not found".
EMAIL_FIELD_CANDIDATES = ("email", "emailAddress", "userEmail", "primaryEmail")

# ---- Source of truth: the two contaminated ids and their true emails ----
CONTAMINATED: dict[str, str] = {
    "user_d604055f03e6": "gautam@infinitylogisticsme.com",
    "user_d51ef8bddcdd": "cibin@infinitylogisticsme.com",
}

# ---- Fields to repair ----
# Format: (collection, field_path, kind)
#   scalar  - plain field, or a dotted path into a nested OBJECT (latestMovement.user).
#             `$set` on the dotted path is correct for both.
#   array   - one array level: <array>.<leaf>
#   array2  - two nested array levels: <outer>.<inner>.<leaf>
#
# PROVENANCE: this list is NOT hand-written from the models. It came from a full
# scan of every document in every collection of wms_pro_tenant_199, walking each
# document tree and flagging any string leaf matching ^user_[0-9a-f]{12}$.
#
# That method exists because the original census in MOBILE-APP-FREIGHAI-MIGRATION.md
# §2.4 was built by GUESSING field names from memory and got 9 of 12 wrong —
# it queried storage_items.lastMovedBy, item_transactions.performedBy,
# quantity_transactions.performedBy and barcode_generation_requests.requestedBy,
# none of which exist in the Kotlin models. A query on a non-existent field returns
# 0, so four large collections were declared clean without ever being examined and
# the blast radius was under-reported as 167 documents instead of 3,190.
#
# If you extend this list, re-run the full scan; do not add paths from memory.
TARGETS: list[tuple[str, str, str]] = [
    ("receiving_records",          "receivingStaff",                    "scalar"),
    ("receiving_records",          "statusHistory.changedBy",           "array"),
    ("order_fulfillment_requests", "createdBy",                         "scalar"),
    ("order_fulfillment_requests", "updatedBy",                         "scalar"),
    ("order_fulfillment_requests", "statusHistory.user",                "array"),
    ("order_fulfillment_requests", "lineItems.allocatedItems.pickedBy", "array2"),
    ("storage_items",              "latestMovement.user",               "scalar"),
    ("item_transactions",          "user",                              "scalar"),
    ("quantity_transactions",      "user",                              "scalar"),
    ("barcode_generation_requests", "createdBy",                        "scalar"),
    ("files",                      "uploadedBy",                        "scalar"),
    ("skus",                       "createdBy",                         "scalar"),
]

# Document counts from the full scan on 2026-08-17. Reported alongside the live
# count purely so drift is visible. Never trusted.
SNAPSHOT: dict[tuple[str, str], int] = {
    ("receiving_records",          "receivingStaff"):                     14,
    ("receiving_records",          "statusHistory.changedBy"):            88,
    ("order_fulfillment_requests", "createdBy"):                          65,
    ("order_fulfillment_requests", "updatedBy"):                          61,
    ("order_fulfillment_requests", "statusHistory.user"):                 65,
    ("order_fulfillment_requests", "lineItems.allocatedItems.pickedBy"):  30,
    ("storage_items",              "latestMovement.user"):              1360,
    ("item_transactions",          "user"):                             1360,
    ("quantity_transactions",      "user"):                              121,
    ("barcode_generation_requests", "createdBy"):                          6,
    ("files",                      "uploadedBy"):                         14,
    ("skus",                       "createdBy"):                           6,
}

SAMPLE_LIMIT = 5


def split_array_path(field_path: str) -> tuple[str, str]:
    """`statusHistory.changedBy` -> (`statusHistory`, `changedBy`)."""
    array_field, _, leaf = field_path.partition(".")
    if not leaf:
        raise ValueError(f"array target {field_path!r} must be <array>.<leaf>")
    return array_field, leaf


def split_array2_path(field_path: str) -> tuple[str, str, str]:
    """`lineItems.allocatedItems.pickedBy` -> (`lineItems`, `allocatedItems`, `pickedBy`)."""
    parts = field_path.split(".")
    if len(parts) != 3:
        raise ValueError(f"array2 target {field_path!r} must be <outer>.<inner>.<leaf>")
    return parts[0], parts[1], parts[2]


def count_target(db, collection: str, field_path: str, kind: str, ids: list[str]) -> tuple[int, int]:
    """Live count for one field. Returns (documents, contaminated_elements).

    For a scalar field the two are equal. For an array field a single document
    can hold several contaminated elements, so both numbers matter.
    """
    coll = db[collection]
    query = {field_path: {"$in": ids}}
    docs = coll.count_documents(query)
    if kind == "scalar":
        return docs, docs

    if kind == "array":
        array_field, leaf = split_array_path(field_path)
        element_expr = {"$size": {"$filter": {
            "input": {"$ifNull": [f"${array_field}", []]},
            "as": "e",
            "cond": {"$in": [f"$$e.{leaf}", ids]},
        }}}
    else:  # array2 — sum the inner matches across every outer element
        outer, inner, leaf = split_array2_path(field_path)
        element_expr = {"$sum": {"$map": {
            "input": {"$ifNull": [f"${outer}", []]},
            "as": "o",
            "in": {"$size": {"$filter": {
                "input": {"$ifNull": [f"$$o.{inner}", []]},
                "as": "i",
                "cond": {"$in": [f"$$i.{leaf}", ids]},
            }}},
        }}}

    pipeline = [
        {"$match": query},
        {"$project": {"n": element_expr}},
        {"$group": {"_id": None, "docs": {"$sum": 1}, "elements": {"$sum": "$n"}}},
    ]
    agg = list(coll.aggregate(pipeline))
    if not agg:
        return docs, 0
    return agg[0]["docs"], agg[0]["elements"]


def sample_ids(db, collection: str, field_path: str, ids: list[str], limit: int = SAMPLE_LIMIT) -> list:
    """A few affected _ids, for eyeballing before an --apply."""
    cur = db[collection].find({field_path: {"$in": ids}}, {"_id": 1}).limit(limit)
    return [d["_id"] for d in cur]


def scan_unknown_ids(db, collection: str, field_path: str) -> list[str]:
    """Any `user_*` value in this field that is NOT one of the two known ids.

    The blast radius was measured on a snapshot. If a third contaminated id has
    appeared since, the operator must know before writing anything.
    """
    try:
        values = db[collection].distinct(field_path, {field_path: {"$regex": "^user_"}})
    except Exception as e:  # noqa: BLE001 - a failed scan must not read as "clean"
        raise RuntimeError(f"unknown-id scan failed on {collection}.{field_path}: {e}") from e
    return sorted(
        v for v in values
        if isinstance(v, str) and v.startswith("user_") and v not in CONTAMINATED
    )


def gather_counts(db, ids: list[str]) -> tuple[list[dict], int, int]:
    """Per-target, per-id live counts. Returns (rows, total_docs, total_elements)."""
    rows: list[dict] = []
    total_docs = total_elements = 0
    for collection, field_path, kind in TARGETS:
        for bad_id in ids:
            docs, elements = count_target(db, collection, field_path, kind, [bad_id])
            rows.append({
                "collection": collection,
                "field": field_path,
                "kind": kind,
                "id": bad_id,
                "docs": docs,
                "elements": elements,
            })
        # Per-target total is counted in one query, not summed: a single document
        # can hold elements from both ids, so summing would double-count docs.
        docs, elements = count_target(db, collection, field_path, kind, ids)
        rows.append({
            "collection": collection,
            "field": field_path,
            "kind": kind,
            "id": "TOTAL",
            "docs": docs,
            "elements": elements,
        })
        total_docs += docs
        total_elements += elements
    return rows, total_docs, total_elements


def print_count_table(rows: list[dict], show_snapshot: bool = True) -> None:
    header = f"  {'Collection.field':<46} {'id':<20} {'docs':>6} {'elements':>9}"
    if show_snapshot:
        header += f" {'snapshot':>9} {'drift':>7}"
    print(header)
    print("  " + "-" * (len(header) - 2))
    last_key = None
    for r in rows:
        key = f"{r['collection']}.{r['field']}"
        label = key if key != last_key else ""
        last_key = key
        line = f"  {label:<46} {r['id']:<20} {r['docs']:>6} {r['elements']:>9}"
        if show_snapshot:
            if r["id"] == "TOTAL":
                snap = SNAPSHOT.get((r["collection"], r["field"]))
                if snap is None:
                    line += f" {'-':>9} {'-':>7}"
                else:
                    # SNAPSHOT holds DOCUMENT counts for every target, so the
                    # comparison must use docs even for array/array2 rows. Comparing
                    # the element count against a document snapshot reported large
                    # phantom drift on rows that had not changed at all.
                    delta = r["docs"] - snap
                    line += f" {snap:>9} {delta:>+7}"
            else:
                line += f" {'':>9} {'':>7}"
        print(line)
        if r["id"] == "TOTAL":
            print()


def resolve_freighai_emails(fai_db) -> tuple[dict[str, str], list[str]]:
    """Confirm both ids against FreighAi. Returns (resolved, errors)."""
    ids = list(CONTAMINATED)
    users = {u["_id"]: u for u in fai_db[FREIGHAI_USERS_COLLECTION].find({"_id": {"$in": ids}})}
    resolved: dict[str, str] = {}
    errors: list[str] = []

    for bad_id, expected in CONTAMINATED.items():
        user = users.get(bad_id)
        if user is None:
            errors.append(
                f"  {bad_id}: NOT FOUND in {FREIGHAI_TENANT_DB}.{FREIGHAI_USERS_COLLECTION}"
            )
            continue

        actual = None
        used_field = None
        for candidate in EMAIL_FIELD_CANDIDATES:
            value = user.get(candidate)
            if isinstance(value, str) and value.strip():
                actual, used_field = value.strip(), candidate
                break
        if actual is None:
            errors.append(
                f"  {bad_id}: no email field on the FreighAi user doc "
                f"(tried {', '.join(EMAIL_FIELD_CANDIDATES)}; doc keys: {sorted(user)})"
            )
            continue

        if actual.lower() != expected.lower():
            errors.append(
                f"  {bad_id}: FreighAi {used_field}={actual!r}, expected {expected!r} — "
                f"mapping is wrong, refusing to write"
            )
            continue

        if "@" not in actual:
            errors.append(f"  {bad_id}: resolved value {actual!r} is not an email address")
            continue

        resolved[bad_id] = actual
        name = user.get("name") or user.get("fullName") or "?"
        print(f"  {bad_id}  ->  {actual}  ({name}) [{used_field}]")

    return resolved, errors


def apply_repair(db, resolved: dict[str, str], applied: list[str]) -> tuple[int, int]:
    """Perform the writes. Returns (matched, modified). Appends to `applied`
    as each op lands so an exception can report exactly how far it got."""
    total_matched = total_modified = 0

    for collection, field_path, kind in TARGETS:
        coll = db[collection]
        print(f"  {collection}.{field_path}:")
        for bad_id, email in resolved.items():
            if kind == "scalar":
                # Also correct for a dotted path into a nested object
                # (latestMovement.user) — $set addresses it directly.
                result = coll.update_many(
                    {field_path: bad_id},
                    {"$set": {field_path: email}},
                )
            elif kind == "array":
                array_field, leaf = split_array_path(field_path)
                result = coll.update_many(
                    {field_path: bad_id},
                    {"$set": {f"{array_field}.$[elem].{leaf}": email}},
                    array_filters=[{f"elem.{leaf}": bad_id}],
                )
            else:  # array2 — two nested array levels
                outer, inner, leaf = split_array2_path(field_path)
                # The outer filter selects only those outer elements that actually
                # contain a matching inner element, so untouched line items are not
                # rewritten; the inner filter pins the exact element to change.
                result = coll.update_many(
                    {field_path: bad_id},
                    {"$set": {f"{outer}.$[o].{inner}.$[i].{leaf}": email}},
                    array_filters=[
                        {f"o.{inner}.{leaf}": bad_id},
                        {f"i.{leaf}": bad_id},
                    ],
                )
            total_matched += result.matched_count
            total_modified += result.modified_count
            applied.append(f"{collection}.{field_path} / {bad_id} -> {email}")
            print(
                f"    {bad_id} -> {email}: "
                f"matched={result.matched_count}, modified={result.modified_count}"
            )
    return total_matched, total_modified


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="Actually write to WMS prod (default: dry-run)")
    parser.add_argument("--verify-only", action="store_true", help="Report live counts and exit; touches nothing, no FreighAi call")
    parser.add_argument("--ignore-unknown-ids", action="store_true", help="Proceed even if a user_* id outside the known two is found")
    args = parser.parse_args()

    if args.verify_only and args.apply:
        print("ERROR: --verify-only and --apply are mutually exclusive", file=sys.stderr)
        return 2

    if args.verify_only:
        mode = "VERIFY ONLY (read-only)"
    elif args.apply:
        mode = "APPLY (live write)"
    else:
        mode = "DRY RUN (read-only)"
    print(f"=== Phase M9 JWT identity backfill — {mode} ===\n")

    wms_uri = os.environ.get("WMS_MONGO_URI", WMS_URI_DEFAULT)
    fai_uri = os.environ.get("FREIGHAI_MONGO_URI", FREIGHAI_URI_DEFAULT)
    print(f"WMS:      {wms_uri.split('@')[-1]}")
    if not args.verify_only:
        print(f"FreighAI: {fai_uri.split('@')[-1]}")

    wms_client = MongoClient(wms_uri)
    wms_central = wms_client[WMS_CENTRAL_DB]
    wms_tenant = wms_client[WMS_TENANT_DB]
    ids = list(CONTAMINATED)

    total_steps = 3 if args.verify_only else 5

    # 1. Sanity: tenant 199 exists and points at the db we are about to edit
    tenant_doc = wms_central["tenant_database_mappings"].find_one({"_id": WMS_TENANT_ID})
    if not tenant_doc:
        print(f"ERROR: tenant {WMS_TENANT_ID} not found in tenant_database_mappings", file=sys.stderr)
        return 1
    mapped_db = tenant_doc.get("mongoConnection", {}).get("databaseName")
    print(f"\n[1/{total_steps}] Tenant: {tenant_doc.get('tenantName')} (db={mapped_db})")
    if mapped_db != WMS_TENANT_DB:
        print(
            f"ERROR: tenant {WMS_TENANT_ID} maps to {mapped_db!r}, but this script targets "
            f"{WMS_TENANT_DB!r}. Refusing to run against the wrong database.",
            file=sys.stderr,
        )
        return 1

    # 2. Live count — never trust the doc's snapshot
    print(f"\n[2/{total_steps}] Live contamination counts (snapshot column = doc §2.4, 2026-08-14):")
    before_rows, before_docs, before_elements = gather_counts(wms_tenant, ids)
    print_count_table(before_rows)
    print(f"  Grand total: {before_docs} documents, {before_elements} contaminated values\n")

    # 3. Anything contaminated that is NOT one of the two known ids
    print(f"[3/{total_steps}] Scanning for unknown user_* ids outside the known two:")
    unknown: dict[str, list[str]] = {}
    for collection, field_path, _kind in TARGETS:
        found = scan_unknown_ids(wms_tenant, collection, field_path)
        if found:
            unknown[f"{collection}.{field_path}"] = found
    if unknown:
        print("  WARNING: contaminated ids found that this script does NOT repair:", file=sys.stderr)
        for where, found in unknown.items():
            print(f"    {where}: {', '.join(found)}", file=sys.stderr)
        if not args.ignore_unknown_ids:
            print(
                "\nERROR: refusing to proceed — the mapping is incomplete. Resolve these ids, add\n"
                "       them to CONTAMINATED, or re-run with --ignore-unknown-ids to repair only\n"
                "       the two known ids and leave the rest contaminated.",
                file=sys.stderr,
            )
            return 1
        print("  --ignore-unknown-ids passed: these will be LEFT CONTAMINATED.")
    else:
        print("  none — the two known ids account for all user_* contamination.")

    if args.verify_only:
        print("\n(Verify only — nothing inspected further, nothing written.)")
        return 0

    # 4. Confirm the id -> email mapping against FreighAi before writing
    print(f"\n[4/{total_steps}] Resolving user ids against {FREIGHAI_TENANT_DB}.{FREIGHAI_USERS_COLLECTION}:")
    fai_client = MongoClient(fai_uri)
    fai_tenant = fai_client[FREIGHAI_TENANT_DB]
    resolved, errors = resolve_freighai_emails(fai_tenant)
    if errors:
        print("ERROR: could not confirm the id -> email mapping:", file=sys.stderr)
        for line in errors:
            print(line, file=sys.stderr)
        return 1
    if len(resolved) != len(CONTAMINATED):
        print(
            f"ERROR: resolved {len(resolved)}/{len(CONTAMINATED)} ids; refusing to write",
            file=sys.stderr,
        )
        return 1
    print(f"  all {len(resolved)} ids confirmed against FreighAi")

    # 5. Repair
    print(f"\n[5/{total_steps}] Repair:")
    applied: list[str] = []
    if not args.apply:
        for collection, field_path, kind in TARGETS:
            samples = sample_ids(wms_tenant, collection, field_path, ids)
            row = next(
                r for r in before_rows
                if r["collection"] == collection and r["field"] == field_path and r["id"] == "TOTAL"
            )
            print(f"  {collection}.{field_path} ({kind}):")
            if row["docs"] == 0:
                print("    [dry-run] nothing to do — already clean")
                continue
            print(
                f"    [dry-run] would update {row['docs']} document(s), "
                f"{row['elements']} value(s)"
            )
            for bad_id, email in resolved.items():
                per = next(
                    r for r in before_rows
                    if r["collection"] == collection and r["field"] == field_path and r["id"] == bad_id
                )
                if per["docs"]:
                    print(
                        f"      {bad_id} -> {email}: "
                        f"{per['docs']} doc(s), {per['elements']} value(s)"
                    )
            if samples:
                print(f"    sample _ids: {', '.join(str(s) for s in samples)}")
        print("\n=== Summary ===")
        print(f"Would repair {before_docs} document(s) / {before_elements} value(s).")
        print("\n(Dry run only — no writes performed. Re-run with --apply to commit.)")
        return 0

    try:
        matched, modified = apply_repair(wms_tenant, resolved, applied)
    except Exception:  # noqa: BLE001 - must never exit silently mid-write
        print("\n!!! PARTIALLY APPLIED — the run failed mid-write !!!", file=sys.stderr)
        if applied:
            print("Operations that DID complete:", file=sys.stderr)
            for line in applied:
                print(f"  {line}", file=sys.stderr)
        else:
            print("No operation completed.", file=sys.stderr)
        print("\nThe script is idempotent: fix the cause and re-run --apply.", file=sys.stderr)
        traceback.print_exc()
        return 1

    print(f"\n  totals: matched={matched}, modified={modified}")

    # Re-verify
    print("\n=== Post-apply verification ===")
    after_rows, after_docs, after_elements = gather_counts(wms_tenant, ids)
    print_count_table(after_rows, show_snapshot=False)

    print("=== Summary ===")
    print(f"before: {before_docs} document(s), {before_elements} contaminated value(s)")
    print(f"after:  {after_docs} document(s), {after_elements} contaminated value(s)")
    print(f"repaired: {before_elements - after_elements} value(s)")

    if after_docs or after_elements:
        print(
            "\nERROR: contamination remains after --apply. Either M1 is not deployed and new\n"
            "       rows are still being written behind this script, or a write was rejected.\n"
            "       Re-run to repair the remainder once M1 is live.",
            file=sys.stderr,
        )
        return 1

    print("\nAll clear — 0 contaminated values remain. Re-running is safe and will report 0 changes.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nERROR: interrupted", file=sys.stderr)
        sys.exit(130)
    except Exception:  # noqa: BLE001 - fail loudly, never exit 0 on error
        traceback.print_exc()
        sys.exit(1)
