# Supplier expenses and billing-run controls

## Behaviour

GRN/GIN → Cost Adjustments has a separate Supplier expenses section. Users enter a total net amount in AED, description, expense date and active FreighAI Charge Type. Supplier name and notes are optional. Customer, project and document number are validated/derived from the tenant's source document. The expense date selects the billing month.

Internal per-CBM movement adjustments are unchanged. Supplier expenses live in `supplier_expenses`; they are never summed into movement adjustments or customer selling lines. Entering the same cost in both features would double-count it, so the guide explicitly prohibits this.

Before generation, entries are OPEN and revision-checked for edits/deletion. Generation reserves each expense using its captured revision and freezes an independent SUPPLIER_EXPENSE cost snapshot. All supplier lines use PARTNER_INVOICE and stable `supplier-{expenseId}` IDs. Cost-only project/month buckets create Warehouse Jobs without Sales Invoices.

After an existing V1 job is generated, saving an expense synchronizes an additive cost amendment. FreighAI stores it in `supplierExpenseAdditions` with an audit entry, preserving the original `commercialSnapshot`, source hash, sales lines and invoices. Effective cost projections include both lists; PI validation, actualization and reconciliation use those effective lines. Cancelled and legacy jobs are rejected with a visible explanation.

FROZEN records are durable retry records; edits are blocked after sync begins, including after uncertain timeouts. Repeating the same expense identity with identical evidence is adopted; changed evidence is rejected. FreighAI uses a job-version CAS to prevent duplicates and handles ACTIVE and BILLED jobs. Actualization overlays are insert-only so retries cannot reset completed lines. WMS adds a local supplemental cost snapshot only once after remote confirmation. Manual retry is available, and the existing tenant-aware worker retries when a valid service-account JWT is configured. No token is stored in expense documents.

The WMS reconciliation report remains a planned-cost/source breakdown. Supplier expense rows carry zero customer revenue and their own cost. FreighAI remains authoritative for posted PI actuals and financial reconciliation.

## Billing actions

Both manual monthly sweeps and customer-specific runs call an unpaginated history preflight. Previous runs or billing records require an explicit Run Anyway confirmation. The POST endpoint independently enforces `confirmRerun`; existing tuple/idempotency protections remain. Cancellation sends no generation request. Customer Master has a Do Billing Run action per customer, and the customer's Billing tab exposes the same preview-first flow.

## Deployment order

1. FreighAI job-order-service and API gateway: additive supplier-expense endpoint, cost-only jobs, effective cost projections and `order:edit` route mapping.
2. WMS Common: publish/install the updated common artifact used by tenant-service (client and permissions).
3. WMS tenant-service: supplier expenses, billing integration, repeat-run server guard.
4. WMS web app and updated billing guide.

No production financial data is migrated or reclassified. Existing storage/inbound/outbound treatments remain unchanged. Configure `canViewWarehouseJobs` and `canManageSupplierExpenses` for the intended WMS staff; the caller also needs FreighAI `order:edit` for additions and the existing Finance permissions for PIs. WMS manager/admin defaults include expense management, but existing explicitly stored permission mappings need an explicit grant through User Management. The background JWT must belong to the matching tenant and have the relevant FreighAI permissions.

Once SUPPLIER_EXPENSE snapshots exist, do not roll tenant-service back to a build whose source-type enum cannot read them. Once FreighAI additions exist, retain the additive-cost readers when rolling back other components, otherwise costs would be omitted from projections. Frontend rollback is independent of these additive backend data changes.

## Verification

Targeted WMS tests cover mixed/supplier-only payloads, absence of an SI command for cost-only jobs, customer/source validation, amount validation, concurrent edit fencing, existing warehouse job recovery tests and repeat-run confirmation. FreighAI tests cover additive costs on billed jobs, unchanged original commercial evidence, idempotent adoption, changed-payload rejection, wrong tenant/currency, cancelled jobs and actual-cost recognition without counting the planned addition twice. Local browser fixtures exercise supplier entry and repeat-run cancellation/confirmation with synthetic data only. No production PI was created as a test.

To repeat UI checks locally: run `bun install --frozen-lockfile` and `npm run dev`, then open `/test/manual/billing.html`. This fixture renders the real components and intercepts every Axios request with synthetic in-memory responses; it must not be used as evidence of a production deployment. The fixture displays the number and scope of submitted runs so Cancel and Run Anyway can be verified directly.
