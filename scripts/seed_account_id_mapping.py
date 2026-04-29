#!/usr/bin/env python3
"""
Seed `account_id_mapping` collection in `wms_pro_tenant_199` with the 47 confirmed
leadtorev → FreighAI customer mappings for the Infinity Logistics tenant.

Also seeds `account_id_sequence` for future lazy-assignment of synthetic IDs to
new FreighAI customers (used by Phase 6 of the migration).

Optionally deletes ASN-2526-0005 (TRYKA — customer not migrated, ASN cancelled
per business decision).

Cross-checks every freighaiCustomerId against FreighAI's customers collection
to ensure each one exists with the matching accountCode before writing anything.

Default: dry-run (prints what would happen, writes nothing). Pass --apply to
actually write to the WMS DB.

Usage:
    python3 seed_account_id_mapping.py                    # dry-run
    python3 seed_account_id_mapping.py --apply            # live write
    python3 seed_account_id_mapping.py --apply --cleanup-tryka  # also delete TRYKA ASN
"""

from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime, timezone

from pymongo import MongoClient, ReplaceOne, UpdateOne, ASCENDING

WMS_URI_DEFAULT = "mongodb://flybizdigi:FlyBizDigi%40123@cloud.leadtorev.com:27170/?authSource=admin"
FREIGHAI_URI_DEFAULT = "mongodb://admin:Fr31ghA1M0ng0Pr0d2026X9K7v@api.freighai.com:27179/?authSource=admin"

WMS_TENANT_DB = "wms_pro_tenant_199"
WMS_CENTRAL_DB = "wms_pro_tenants"
FREIGHAI_TENANT_DB = "freighai_testco"

MAPPING_COLLECTION = "account_id_mapping"
SEQUENCE_COLLECTION = "account_id_sequence"
SEQUENCE_DOC_ID = "next_id"
SEQUENCE_START = 1_000_000

TRYKA_ASN_ID = "ASN-2526-0005"
TRYKA_LEADTOREV_ID = 6996

# ---- Source of truth: 47 confirmed leadtorev → FreighAI customer mappings ----
# Format: (leadtorev_account_id, leadtorev_account_code, freighai_customer_id, source, notes)
# source: leadtorev_match | leadtorev_manual | leadtorev_consolidated | freighai_created
MAPPINGS: list[tuple[int, str, str, str, str | None]] = [
    (924,  "HIETAG", "cust_a0ff436ecb3f", "leadtorev_match",        "Infinity Logistics F.Z.E. — matched via shared internal email"),
    (4607, "LGRXCV", "cust_921a4b1bb7fd", "leadtorev_manual",       "Logistica - Al Rai (manual map provided by user)"),
    (5159, "KUDATL", "cust_ecf97ed75ab0", "leadtorev_match",        "Cargoz.com → Cargoz FZE"),
    (4556, "DWVPYF", "cust_34cc024f59a2", "leadtorev_consolidated", "TEXTURE GLOBAL SHIPPING LLC — leadtorev duplicate of 6900 (GSNYWZ); same FreighAI customer per user"),
    (5441, "VEIFKQ", "cust_e29265e68e91", "leadtorev_match",        "STEADFAST CARGO SERVICES → Steadfast Cargo Services LLC"),
    (5802, "TFULVO", "cust_060884af8a95", "leadtorev_consolidated", "Sinotrans — consolidated with VOPXKJ (6834) per user; FreighAI customer carries VOPXKJ code"),
    (6161, "TNDIUH", "cust_5d0bf2b4fecb", "leadtorev_match",        None),
    (6176, "GXYIUH", "cust_eea403e7758f", "leadtorev_match",        None),
    (6190, "WUMIRK", "cust_71333d61b79e", "leadtorev_match",        None),
    (6272, "UQJVGK", "cust_4322d259addf", "leadtorev_match",        None),
    (6346, "BMNJUS", "cust_c09849a7f0a8", "leadtorev_match",        None),
    (6373, "ELPVOM", "cust_f3811c66ddd3", "leadtorev_match",        None),
    (6393, "RMIEXS", "cust_40174faf5513", "leadtorev_match",        None),
    (6433, "GQLWCX", "cust_3111416f7131", "leadtorev_match",        None),
    (6487, "TRELAZ", "cust_474851ace7e6", "leadtorev_match",        None),
    (6497, "CYFQJN", "cust_3b8cb6da4443", "leadtorev_match",        None),
    (6508, "IXLMOC", "cust_e0b8c960f2e4", "leadtorev_match",        None),
    (6512, "IPGLNK", "cust_cf542ac1e79c", "leadtorev_match",        None),
    (6528, "JDNTYW", "cust_4ddd0d7dad53", "leadtorev_match",        None),
    (6533, "XRKHBN", "cust_7dd89921b4eb", "leadtorev_match",        "AHMED SEDDIQI & SONS LLC"),
    (6551, "ELMIYP", "cust_c0cd910c66c6", "leadtorev_match",        None),
    (6586, "LMZJRP", "cust_7561d444254a", "leadtorev_match",        None),
    (6606, "UOCGIP", "cust_8bfa1147d01b", "leadtorev_match",        None),
    (6742, "OWQMHC", "cust_bb72a5c5c643", "leadtorev_match",        None),
    (6751, "XVCOPQ", "cust_be5b06691934", "leadtorev_match",        None),
    (6791, "GBQYHT", "cust_106a9e7031d0", "leadtorev_match",        None),
    (6797, "OFZCUE", "cust_48d8b5ff8982", "leadtorev_match",        None),
    (6817, "EVLRJB", "cust_adb0b35950a4", "leadtorev_match",        None),
    (6834, "VOPXKJ", "cust_060884af8a95", "leadtorev_match",        "Overseas Development LLC (Globe Express Services) — also receives Sinotrans (5802) traffic"),
    (6837, "LZJWRQ", "cust_03292cbdc098", "leadtorev_match",        None),
    (6867, "CXFEAS", "cust_6d9833a1f935", "leadtorev_match",        None),
    (6869, "ZUMWIB", "cust_a8dcd1218b12", "leadtorev_match",        None),
    (6900, "GSNYWZ", "cust_34cc024f59a2", "leadtorev_match",        None),
    (6903, "JVWEOB", "cust_635f8b923a78", "leadtorev_match",        None),
    (6912, "GKHRVS", "cust_9d8e3f8cabb7", "leadtorev_match",        None),
    (6929, "CHMITV", "cust_d12d4c95d9b6", "leadtorev_match",        None),
    (6935, "QGBZTJ", "cust_3b9af53ba218", "leadtorev_match",        None),
    (6991, "OQWHBV", "cust_7eb3788d1968", "leadtorev_match",        "Dar Reza Company Ltd LLC"),
    (6992, "QAZEPS", "cust_3f755b2f12c5", "leadtorev_match",        "PME Middle East → PME ENGINEERING SOLUTIONS L.L.C"),
    (6994, "XPLBZA", "cust_302ae323ad4a", "leadtorev_match",        "Digital Stout — FZE/FZC variant, same entity"),
    (6995, "EDBTXW", "cust_316cdbbfa408", "leadtorev_match",        None),
    # 6996 MDCOLA TRYKA — INTENTIONALLY NOT IN MAPPING. ASN-2526-0005 deleted; no migration.
    (6997, "ARIMFY", "cust_24d8e12d343c", "leadtorev_match",        None),
    (6998, "YEANMC", "cust_d99b4101eeef", "freighai_created",       "GoBot - F.Z.C — created via FreighAI API on 2026-04-28"),
    (6999, "SVMPID", "cust_b2a218a5582a", "leadtorev_match",        None),
    (7000, "UPTBLY", "cust_ade636e86e4e", "leadtorev_match",        None),
    (7001, "PALTIW", "cust_1980219460ae", "leadtorev_match",        None),
    (7006, "JAVXMF", "cust_e8a10e53a490", "freighai_created",       "PROJECT 4*8 L.L.C - FZ — created via FreighAI API on 2026-04-28"),
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="Actually write to WMS prod (default: dry-run)")
    parser.add_argument("--cleanup-tryka", action="store_true", help=f"Also delete {TRYKA_ASN_ID} from {WMS_TENANT_DB}.asns")
    args = parser.parse_args()

    mode = "APPLY (live write)" if args.apply else "DRY RUN (read-only)"
    print(f"=== Phase 2 seed — {mode} ===\n")

    wms_uri = os.environ.get("WMS_MONGO_URI", WMS_URI_DEFAULT)
    fai_uri = os.environ.get("FREIGHAI_MONGO_URI", FREIGHAI_URI_DEFAULT)
    print(f"WMS:      {wms_uri.split('@')[-1]}")
    print(f"FreighAI: {fai_uri.split('@')[-1]}")

    wms_client = MongoClient(wms_uri)
    fai_client = MongoClient(fai_uri)
    wms_central = wms_client[WMS_CENTRAL_DB]
    wms_tenant = wms_client[WMS_TENANT_DB]
    fai_tenant = fai_client[FREIGHAI_TENANT_DB]

    # 1. Sanity: tenant 199 exists
    tenant_doc = wms_central["tenant_database_mappings"].find_one({"_id": 199})
    if not tenant_doc:
        print("ERROR: tenant 199 not found in tenant_database_mappings", file=sys.stderr)
        return 1
    print(f"\n[1/6] Tenant: {tenant_doc.get('tenantName')} (db={tenant_doc.get('mongoConnection', {}).get('databaseName')})")

    # 2. Validate mapping data structurally
    seen_ids: set[int] = set()
    for m in MAPPINGS:
        if m[0] in seen_ids:
            print(f"ERROR: duplicate leadtorev id in MAPPINGS: {m[0]}", file=sys.stderr)
            return 1
        seen_ids.add(m[0])
    print(f"[2/6] Mapping rows defined: {len(MAPPINGS)} (expecting 47)")
    if len(MAPPINGS) != 47:
        print(f"WARN: expected 47 mappings, found {len(MAPPINGS)}", file=sys.stderr)

    # 3. Cross-check every freighaiCustomerId against FreighAI's customers
    print(f"\n[3/6] Cross-checking FreighAI customers...")
    fc_ids = list({m[2] for m in MAPPINGS})
    fai_customers = {c["_id"]: c for c in fai_tenant["customers"].find(
        {"_id": {"$in": fc_ids}}, {"_id": 1, "name": 1, "accountCode": 1}
    )}
    mismatches: list[str] = []
    missing: list[str] = []
    for ltr_id, ltr_code, fc_id, source, notes in MAPPINGS:
        c = fai_customers.get(fc_id)
        if c is None:
            missing.append(f"  leadtorev {ltr_id} ({ltr_code}) → {fc_id}: NOT FOUND in FreighAI")
            continue
        # Consolidated case: 5802 expects FreighAI to carry VOPXKJ (not TFULVO). Skip code check there.
        if source == "leadtorev_consolidated":
            continue
        if c.get("accountCode") != ltr_code:
            mismatches.append(
                f"  leadtorev {ltr_id} ({ltr_code}) → {fc_id} ({c.get('name')!r}): "
                f"FreighAI accountCode={c.get('accountCode')!r}, expected {ltr_code!r}"
            )
    if missing:
        print("ERROR: some FreighAI customers do not exist:", file=sys.stderr)
        for line in missing:
            print(line, file=sys.stderr)
        return 1
    if mismatches:
        print("ERROR: accountCode mismatches between mapping and FreighAI:", file=sys.stderr)
        for line in mismatches:
            print(line, file=sys.stderr)
        return 1
    print(f"  all {len(fc_ids)} FreighAI customers resolved with matching accountCodes")

    # 4. TRYKA ASN cleanup
    print(f"\n[4/6] TRYKA ASN cleanup ({TRYKA_ASN_ID}, leadtorev {TRYKA_LEADTOREV_ID}):")
    tryka_asn = wms_tenant["asns"].find_one({"_id": TRYKA_ASN_ID})
    if not tryka_asn:
        print(f"  {TRYKA_ASN_ID}: not present (already deleted or never existed)")
    else:
        print(f"  {TRYKA_ASN_ID}: present (status={tryka_asn.get('status')}, supplier={tryka_asn.get('supplierName')!r})")
        if args.cleanup_tryka and args.apply:
            r = wms_tenant["asns"].delete_one({"_id": TRYKA_ASN_ID})
            print(f"  deleted: {r.deleted_count} doc")
        elif args.cleanup_tryka:
            print(f"  [dry-run] would delete this doc")
        else:
            print(f"  --cleanup-tryka not passed; doc left in place")

    # 5. Mapping seed
    now = datetime.now(timezone.utc)
    print(f"\n[5/6] Seeding {MAPPING_COLLECTION}:")
    coll = wms_tenant[MAPPING_COLLECTION]
    existing_count = coll.count_documents({})
    print(f"  existing rows: {existing_count}")

    if args.apply:
        ops = []
        for ltr_id, ltr_code, fc_id, source, notes in MAPPINGS:
            doc = {
                "_id": ltr_id,
                "freighaiCustomerId": fc_id,
                "accountCode": ltr_code,
                "source": source,
                "notes": notes,
                "updatedAt": now,
            }
            existing = coll.find_one({"_id": ltr_id}, {"createdAt": 1})
            if existing and existing.get("createdAt"):
                doc["createdAt"] = existing["createdAt"]
            else:
                doc["createdAt"] = now
            ops.append(ReplaceOne({"_id": ltr_id}, doc, upsert=True))
        if ops:
            result = coll.bulk_write(ops, ordered=False)
            print(f"  upserts: matched={result.matched_count}, modified={result.modified_count}, upserted={len(result.upserted_ids)}")
        # Index on freighaiCustomerId (non-unique due to consolidation)
        coll.create_index([("freighaiCustomerId", ASCENDING)], name="idx_freighaiCustomerId")
        print(f"  index created: idx_freighaiCustomerId on {{freighaiCustomerId: 1}}")
    else:
        print(f"  [dry-run] would upsert {len(MAPPINGS)} mapping rows")
        print(f"  [dry-run] would create index idx_freighaiCustomerId")

    # 6. Sequence seed
    print(f"\n[6/6] Seeding {SEQUENCE_COLLECTION}:")
    seq = wms_tenant[SEQUENCE_COLLECTION].find_one({"_id": SEQUENCE_DOC_ID})
    if seq:
        print(f"  already exists: value={seq.get('value')}")
    else:
        if args.apply:
            wms_tenant[SEQUENCE_COLLECTION].update_one(
                {"_id": SEQUENCE_DOC_ID},
                {"$setOnInsert": {"value": SEQUENCE_START, "createdAt": now}, "$set": {"updatedAt": now}},
                upsert=True,
            )
            print(f"  created with value={SEQUENCE_START}")
        else:
            print(f"  [dry-run] would create with value={SEQUENCE_START}")

    # Final verification
    print(f"\n=== Final state ===")
    final_count = coll.count_documents({})
    print(f"{MAPPING_COLLECTION}: {final_count} rows" + ("" if args.apply else " (would be 47 after --apply)"))
    print(f"{SEQUENCE_COLLECTION}: {wms_tenant[SEQUENCE_COLLECTION].count_documents({})} doc")

    if args.apply:
        # Spot-check
        sample_ids = [4607, 5802, 6533, 6834, 6998, 7006]
        print(f"\nSpot-check ({len(sample_ids)} rows):")
        for sid in sample_ids:
            d = coll.find_one({"_id": sid})
            if d:
                print(f"  {sid:>5}  {d['accountCode']}  → {d['freighaiCustomerId']}  [{d['source']}]")
            else:
                print(f"  {sid:>5}  MISSING")
    else:
        print("\n(Dry run only — no writes performed. Re-run with --apply to commit.)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
