package com.wmspro.tenant.billing.invoice.aggregator

import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component

/**
 * Works out which warehouse stock, receipts and shipments belong to, for customers invoiced per
 * warehouse. Reads the tenant collections directly, like the aggregators.
 *
 * Every bin and zone code starts with its warehouse id ("WH1-B-2-1-B", zone "WH1-A"), so a
 * location maps to a warehouse by prefix. Pseudo-locations (UNDER_RECEIVING, IN_TRANSIT, ...)
 * belong to no warehouse.
 */
@Component
class WarehouseAttribution(
    private val mongoTemplate: MongoTemplate
) {
    /** Warehouse id → display name, e.g. "WH1" → "Infinity DIP Warehouse". */
    fun warehouseNames(): Map<String, String> =
        mongoTemplate.find(Query(), Document::class.java, "warehouses")
            .associate { it["_id"].toString() to ((it["name"] as? String)?.takeIf(String::isNotBlank) ?: it["_id"].toString()) }

    fun warehouseOf(locationCode: String?, warehouseIds: Set<String>): String? =
        locationCode?.substringBefore('-')?.takeIf { it in warehouseIds }

    /** The warehouse each receiving record was received into. */
    fun receivingRecordWarehouses(receivingRecordIds: Collection<String>): Map<String, String?> {
        if (receivingRecordIds.isEmpty()) return emptyMap()
        return mongoTemplate.find(
            Query(Criteria.where("_id").`in`(receivingRecordIds)).apply { fields().include("warehouseId") },
            Document::class.java,
            "receiving_records"
        ).associate { it["_id"].toString() to (it["warehouseId"] as? String)?.takeIf(String::isNotBlank) }
    }

    /**
     * The warehouse each outbound order shipped from.
     *
     * Orders carry no warehouse of their own, and their recorded pick location is usually just
     * IN_TRANSIT. The shipped items' own departure moves do say where they left from, so the order
     * belongs to the warehouse most of its items departed from - preferring departures written for
     * this order over any other shipment of the same items. Quantity-based orders fall back to the
     * warehouse of the lots they drew from.
     */
    @Suppress("UNCHECKED_CAST")
    fun fulfillmentWarehouses(fulfillmentIds: Collection<String>, warehouseIds: Set<String>): Map<String, String?> {
        if (fulfillmentIds.isEmpty()) return emptyMap()

        val orders = mongoTemplate.find(
            Query(Criteria.where("_id").`in`(fulfillmentIds)).apply {
                fields().include("packages.assignedItems.storageItemId")
                    .include("lineItems.allocatedItems.storageItemId")
                    .include("lineItems.quantityInventoryReferences.quantityInventoryId")
            },
            Document::class.java,
            "order_fulfillment_requests"
        )

        val itemsByOrder = orders.associate { order ->
            val fromPackages = (order["packages"] as? List<Document>).orEmpty()
                .flatMap { (it["assignedItems"] as? List<Document>).orEmpty() }
            val fromLines = (order["lineItems"] as? List<Document>).orEmpty()
                .flatMap { (it["allocatedItems"] as? List<Document>).orEmpty() }
            // Quantity-based packages carry storageItemId 0 as a placeholder.
            order["_id"].toString() to (fromPackages + fromLines)
                .mapNotNull { (it["storageItemId"] as? Number)?.toLong()?.takeIf { id -> id > 0 } }
                .toSet()
        }
        val lotsByOrder = orders.associate { order ->
            order["_id"].toString() to (order["lineItems"] as? List<Document>).orEmpty()
                .flatMap { (it["quantityInventoryReferences"] as? List<Document>).orEmpty() }
                .mapNotNull { it["quantityInventoryId"] as? String }
                .toSet()
        }

        val allItems = itemsByOrder.values.flatten().toSet()
        val departures = if (allItems.isEmpty()) emptyList() else mongoTemplate.find(
            Query(
                Criteria().andOperator(
                    Criteria.where("storageItemId").`in`(allItems),
                    Criteria.where("transactionType").`is`("LOCATION_CHANGE"),
                    Criteria.where("afterLocation.locationCode").`in`(OFFSITE_LOCATION_CODES)
                )
            ),
            Document::class.java,
            "item_transactions"
        )
        val departuresByItem = departures.groupBy { (it["storageItemId"] as Number).toLong() }

        val lotWarehouses = lotWarehouses(lotsByOrder.values.flatten().toSet(), warehouseIds)

        return fulfillmentIds.associateWith { orderId ->
            val itemDepartures = itemsByOrder[orderId].orEmpty().flatMap { departuresByItem[it].orEmpty() }
            val forThisOrder = itemDepartures.filter {
                it["triggerTaskId"] == orderId || (it["notes"] as? String)?.contains(orderId) == true
            }
            val fromItems = mostCommon(
                forThisOrder.ifEmpty { itemDepartures }
                    .map { warehouseOf((it["beforeLocation"] as? Document)?.getString("locationCode"), warehouseIds) }
            )
            fromItems ?: mostCommon(lotsByOrder[orderId].orEmpty().map { lotWarehouses[it] })
        }
    }

    /**
     * The warehouse of each quantity-based lot: where most of its allocated quantity sits, else the
     * warehouse of the receipt that created it.
     */
    @Suppress("UNCHECKED_CAST")
    fun lotWarehouses(lotIds: Collection<String>, warehouseIds: Set<String>): Map<String, String?> {
        if (lotIds.isEmpty()) return emptyMap()
        val lots = mongoTemplate.find(
            Query(Criteria.where("_id").`in`(lotIds)).apply {
                fields().include("locationAllocations").include("receivingRecordId")
            },
            Document::class.java,
            "quantity_based_inventory"
        )
        val receiptWarehouses = receivingRecordWarehouses(lots.mapNotNull { it["receivingRecordId"] as? String })
        return lots.associate { lot ->
            val byAllocation = (lot["locationAllocations"] as? List<Document>).orEmpty()
                .groupBy { warehouseOf(it["locationCode"] as? String, warehouseIds) }
                .filterKeys { it != null }
                .maxByOrNull { (_, allocations) -> allocations.sumOf { (it["quantity"] as? Number)?.toInt() ?: 0 } }
                ?.key
            lot["_id"].toString() to (byAllocation ?: (lot["receivingRecordId"] as? String)?.let { receiptWarehouses[it] })
        }
    }

    private fun mostCommon(warehouses: List<String?>): String? =
        warehouses.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    companion object {
        /** Pseudo-locations meaning the stock has left the warehouse. */
        val OFFSITE_LOCATION_CODES = setOf("IN_TRANSIT", "CUSTOMER", "CARRIER")

        fun isOffsite(locationCode: String?): Boolean = locationCode?.uppercase() in OFFSITE_LOCATION_CODES
    }
}
