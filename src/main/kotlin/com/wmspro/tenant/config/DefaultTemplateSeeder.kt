package com.wmspro.tenant.config

import com.wmspro.tenant.model.*
import com.wmspro.tenant.repository.DocumentTemplateRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Seeds default document templates on application startup
 */
@Component
class DefaultTemplateSeeder(
    private val documentTemplateRepository: DocumentTemplateRepository
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        logger.info("Checking and seeding default document templates...")

        // Seed default GRN template
        seedDefaultGrnTemplate()

        logger.info("Default template seeding completed")
    }

    private fun seedDefaultGrnTemplate() {
        val exists = documentTemplateRepository.existsByDocumentTypeAndIsDefaultTrue(DocumentType.GRN)

        if (!exists) {
            logger.info("Default GRN template not found, creating...")

            val defaultGrnTemplate = DocumentTemplate(
                tenantId = null, // Global default template (used by all tenants)
                documentType = DocumentType.GRN,
                templateName = "Default GRN Template",
                templateVersion = "1.0",
                htmlTemplate = getDefaultGrnHtmlTemplate(),
                cssContent = getDefaultGrnCssTemplate(),
                commonConfig = CommonConfig(
                    logoUrl = null,
                    companyName = null,
                    primaryColor = "#000000",
                    secondaryColor = "#666666",
                    fontFamily = "Arial, sans-serif",
                    pageSize = "A4",
                    orientation = "portrait",
                    margins = PageMargins(
                        top = "20mm",
                        right = "15mm",
                        bottom = "20mm",
                        left = "15mm"
                    )
                ),
                documentConfig = getDefaultGrnDocumentConfig(),
                isActive = true,
                isDefault = true
            )

            documentTemplateRepository.save(defaultGrnTemplate)
            logger.info("Default GRN template created successfully")
        } else {
            logger.info("Default GRN template already exists, skipping")
        }
    }

    private fun getDefaultGrnDocumentConfig(): Map<String, Any> {
        return mapOf(
            // Header fields
            "showOwner" to true,
            "showCompany" to true,
            "showReceiptNumber" to true,
            "showASNNumber" to true,
            "showExternalReceipt" to false,
            "showCustomerRef" to false,
            "showWarehouseRef" to false,
            "showDateReceived" to true,
            "showTransactionDate" to true,
            "showBOE" to false,

            // Vehicle & Driver info
            "showVehicleNo" to true,
            "showContainerNo" to false,
            "showSealNo" to false,
            "showOffloadingTimes" to true,
            "showDriverName" to false,
            "showDriverMobile" to false,
            "showDriverPhoto" to false,
            "showStatus" to true,

            // Item table columns
            "showSKU" to true,
            "showDescription" to true,
            "showExpectedQty" to true,
            "showReceivedQty" to true,
            "showDamage" to true,
            "showShortExcess" to true,
            "showLength" to true,
            "showWidth" to true,
            "showHeight" to true,
            "showCBM" to true,
            "showWeight" to false,

            // Summary fields
            "showPalletCount" to true,
            "showBoxCount" to false,
            "showTotalCBM" to true,
            "showTotalWeight" to false
        )
    }

    private fun getDefaultGrnHtmlTemplate(): String {
        return """
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>GRN - Goods Receipt Note</title>
    <style th:if="${"$"}{cssContent}" th:utext="${"$"}{cssContent}"></style>
</head>
<body>
    <div class="grn-document">
        <!-- Header Section -->
        <div class="header">
            <div class="logo-section" th:if="${"$"}{commonConfig.logoUrl}">
                <img th:src="${"$"}{commonConfig.logoUrl}" alt="Company Logo" class="logo" />
            </div>
            <div class="title-section">
                <h1 class="document-title">GRN</h1>
                <p class="subtitle">Goods Receipt Note</p>
            </div>
        </div>

        <!-- Info Section - Two Columns -->
        <div class="info-section">
            <div class="info-column">
                <div class="info-row" th:if="${"$"}{documentConfig.showOwner}">
                    <span class="label">Owner:</span>
                    <span class="value" th:text="${"$"}{data.owner}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showReceiptNumber}">
                    <span class="label">Receipt #:</span>
                    <span class="value" th:text="${"$"}{data.receiptNumber}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showASNNumber}">
                    <span class="label">ASN Number:</span>
                    <span class="value" th:text="${"$"}{data.asnNumber ?: 'N/A'}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showCustomerRef}">
                    <span class="label">Customer Ref:</span>
                    <span class="value" th:text="${"$"}{data.customerRef ?: '-'}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showDateReceived}">
                    <span class="label">Date Received:</span>
                    <span class="value" th:text="${"$"}{#temporals.format(data.dateReceived, 'MMMM dd, yyyy')}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showBOE}">
                    <span class="label">BOE:</span>
                    <span class="value" th:text="${"$"}{data.boe ?: '-'}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showPalletCount}">
                    <span class="label">Pallet:</span>
                    <span class="value" th:text="${"$"}{data.palletCount}">-</span>
                </div>
            </div>

            <div class="info-column">
                <div class="info-row" th:if="${"$"}{documentConfig.showCompany}">
                    <span class="label">Company:</span>
                    <span class="value" th:text="${"$"}{data.company}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showVehicleNo}">
                    <span class="label">Vehicle No:</span>
                    <span class="value" th:text="${"$"}{data.vehicleNo ?: '-'}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showContainerNo}">
                    <span class="label">Container No:</span>
                    <span class="value" th:text="${"$"}{data.containerNo ?: '-'}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showSealNo}">
                    <span class="label">Seal No:</span>
                    <span class="value" th:text="${"$"}{data.sealNo ?: '-'}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showOffloadingTimes && data.offloadingStartTime != null}">
                    <span class="label">Offloading Start Time:</span>
                    <span class="value" th:text="${"$"}{#temporals.format(data.offloadingStartTime, 'MMMM dd, yyyy')}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showOffloadingTimes && data.offloadingEndTime != null}">
                    <span class="label">Offloading End Time:</span>
                    <span class="value" th:text="${"$"}{#temporals.format(data.offloadingEndTime, 'MMMM dd, yyyy')}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showTransactionDate}">
                    <span class="label">Transaction Date:</span>
                    <span class="value" th:text="${"$"}{#temporals.format(data.transactionDate, 'MMMM dd, yyyy')}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showDriverName && data.driverName != null}">
                    <span class="label">Driver Name:</span>
                    <span class="value" th:text="${"$"}{data.driverName}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showDriverMobile && data.driverMobile != null}">
                    <span class="label">Mobile No:</span>
                    <span class="value" th:text="${"$"}{data.driverMobile}">-</span>
                </div>
                <div class="info-row" th:if="${"$"}{documentConfig.showStatus}">
                    <span class="label">Status:</span>
                    <span class="value" th:text="${"$"}{data.status}">-</span>
                </div>
            </div>
        </div>

        <!-- Items Table -->
        <table class="items-table">
            <thead>
                <tr>
                    <th th:if="${"$"}{documentConfig.showSKU}">SKU</th>
                    <th th:if="${"$"}{documentConfig.showDescription}">Description</th>
                    <th th:if="${"$"}{documentConfig.showExpectedQty && data.asnNumber != null}">Expected Qty</th>
                    <th th:if="${"$"}{documentConfig.showReceivedQty}">Received Qty</th>
                    <th th:if="${"$"}{documentConfig.showDamage}">Damage</th>
                    <th th:if="${"$"}{documentConfig.showShortExcess}">Short/Excess</th>
                    <th th:if="${"$"}{documentConfig.showLength}">Length</th>
                    <th th:if="${"$"}{documentConfig.showWidth}">Width</th>
                    <th th:if="${"$"}{documentConfig.showHeight}">Height</th>
                    <th th:if="${"$"}{documentConfig.showCBM}">CBM</th>
                    <th th:if="${"$"}{documentConfig.showWeight}">WEIGHT</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="item : ${"$"}{data.items}">
                    <td th:if="${"$"}{documentConfig.showSKU}" th:text="${"$"}{item.itemCode}">-</td>
                    <td th:if="${"$"}{documentConfig.showDescription}" th:text="${"$"}{item.description}">-</td>
                    <td th:if="${"$"}{documentConfig.showExpectedQty && data.asnNumber != null}" th:text="${"$"}{item.expectedQty}">-</td>
                    <td th:if="${"$"}{documentConfig.showReceivedQty}" th:text="${"$"}{item.receivedQty}">-</td>
                    <td th:if="${"$"}{documentConfig.showDamage}" th:text="${"$"}{item.damage}">-</td>
                    <td th:if="${"$"}{documentConfig.showShortExcess}" th:text="${"$"}{item.shortExcess}">-</td>
                    <td th:if="${"$"}{documentConfig.showLength}" th:text="${"$"}{item.length ?: '-'}">-</td>
                    <td th:if="${"$"}{documentConfig.showWidth}" th:text="${"$"}{item.width ?: '-'}">-</td>
                    <td th:if="${"$"}{documentConfig.showHeight}" th:text="${"$"}{item.height ?: '-'}">-</td>
                    <td th:if="${"$"}{documentConfig.showCBM}" th:text="${"$"}{item.cbm ?: '-'}">-</td>
                    <td th:if="${"$"}{documentConfig.showWeight}" th:text="${"$"}{item.weight ?: '-'}">-</td>
                </tr>
            </tbody>
            <tfoot>
                <tr class="totals-row">
                    <td th:if="${"$"}{documentConfig.showSKU}" class="total-label">TOTAL:</td>
                    <td th:if="${"$"}{documentConfig.showDescription}"></td>
                    <td th:if="${"$"}{documentConfig.showExpectedQty && data.asnNumber != null}" th:text="${"$"}{data.totalExpected}">0</td>
                    <td th:if="${"$"}{documentConfig.showReceivedQty}" th:text="${"$"}{data.totalReceived}">0</td>
                    <td th:if="${"$"}{documentConfig.showDamage}" th:text="${"$"}{data.totalDamage}">0</td>
                    <td th:if="${"$"}{documentConfig.showShortExcess}" th:text="${"$"}{data.totalShortExcess}">0</td>
                    <td th:if="${"$"}{documentConfig.showLength}"></td>
                    <td th:if="${"$"}{documentConfig.showWidth}"></td>
                    <td th:if="${"$"}{documentConfig.showHeight}"></td>
                    <td th:if="${"$"}{documentConfig.showCBM && documentConfig.showTotalCBM}" th:text="${"$"}{data.totalCBM}">0</td>
                    <td th:if="${"$"}{documentConfig.showWeight && documentConfig.showTotalWeight}" th:text="${"$"}{data.totalWeight}">0</td>
                </tr>
            </tfoot>
        </table>

        <!-- Footer Section -->
        <div class="footer">
            <p class="generated-text">Generated on <span th:text="${"$"}{#temporals.format(data.generatedAt, 'MMMM dd, yyyy HH:mm')}"></span></p>
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    private fun getDefaultGrnCssTemplate(): String {
        return """
body {
    font-family: Arial, sans-serif;
    margin: 0;
    padding: 20px;
    font-size: 12px;
    color: #000000;
}

.grn-document {
    max-width: 100%;
    margin: 0 auto;
}

/* Header */
.header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    border-bottom: 2px solid #000000;
    padding-bottom: 10px;
}

.logo {
    max-width: 150px;
    max-height: 80px;
}

.title-section {
    text-align: right;
}

.document-title {
    font-size: 32px;
    font-weight: bold;
    margin: 0;
    color: #000000;
}

.subtitle {
    font-size: 14px;
    margin: 0;
    color: #666666;
}

/* Info Section */
.info-section {
    display: flex;
    justify-content: space-between;
    margin-bottom: 20px;
    gap: 20px;
}

.info-column {
    flex: 1;
}

.info-row {
    display: flex;
    margin-bottom: 8px;
    line-height: 1.4;
}

.info-row .label {
    font-weight: bold;
    min-width: 140px;
    color: #000000;
}

.info-row .value {
    flex: 1;
    color: #333333;
}

/* Items Table */
.items-table {
    width: 100%;
    border-collapse: collapse;
    margin-bottom: 20px;
}

.items-table th {
    background-color: #f0f0f0;
    border: 1px solid #000000;
    padding: 8px;
    text-align: left;
    font-weight: bold;
    font-size: 11px;
}

.items-table td {
    border: 1px solid #cccccc;
    padding: 6px 8px;
    font-size: 11px;
}

.items-table tbody tr:nth-child(even) {
    background-color: #fafafa;
}

.items-table tfoot .totals-row {
    background-color: #f0f0f0;
    font-weight: bold;
}

.items-table tfoot .totals-row td {
    border: 1px solid #000000;
}

.total-label {
    text-align: right;
    font-weight: bold;
}

/* Footer */
.footer {
    margin-top: 30px;
    text-align: center;
    font-size: 10px;
    color: #666666;
}

.generated-text {
    margin: 0;
}

/* Page break control for printing */
@media print {
    body {
        padding: 0;
    }

    .grn-document {
        page-break-after: avoid;
    }

    .items-table {
        page-break-inside: auto;
    }

    .items-table tr {
        page-break-inside: avoid;
        page-break-after: auto;
    }
}
        """.trimIndent()
    }
}
