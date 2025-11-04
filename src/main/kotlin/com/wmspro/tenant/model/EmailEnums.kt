package com.wmspro.tenant.model

/**
 * Status for Email Configuration
 */
enum class EmailConfigStatus {
    ACTIVE,
    INACTIVE
}

/**
 * Types of email templates supported
 */
enum class EmailTemplateType {
    GRN,
    GIN,
    INVOICE,
    PACKAGING_LIST,
    DELIVERY_NOTE
}

/**
 * Status for Email Template
 */
enum class EmailTemplateStatus {
    ACTIVE,
    INACTIVE
}
