# System Settings API Specification

Base Path: `/api/v1/tenant-settings`

## Important: Field Naming Convention

Throughout this API, the following terms are **interchangeable** and refer to the same identifier:

| Entity | Equivalent Terms |
|--------|------------------|
| Email Config | `configKey` |
| Email Template | `templateType` |

---

## Frontend Implementation Notes

System Settings APIs manage tenant-level configurations. These settings are tenant-scoped and automatically use the authenticated tenant context.

**Dropdown Fields:**

| Field | Input Type | Options | Notes |
|-------|------------|---------|-------|
| `templateType` | Dropdown | `GRN`, `GIN`, `INVOICE`, `PACKING_LIST`, `DELIVERY_NOTE` | Email template type selector |
| `emailConfigKey` | Dropdown | Fetch from `GET /api/v1/tenant-settings/email-configs` | Reference to email config for templates |

> **References:**
> - Email Configs API: Use List Email Configs endpoint to populate `emailConfigKey` dropdown
> - The `emailConfigKey` in email templates references which SMTP config to use for sending

**Form Field Types:**

| Section | Field | Input Type | Notes |
|---------|-------|------------|-------|
| **SLA Settings** | `*SlaMinutes` | Number Input | All SLA values in minutes (1-10080) |
| **SLA Settings** | `escalationAfterMinutes` | Number Input | Minutes before escalation |
| **Email Config** | `configKey` | Text Input | Unique identifier (e.g., "default", "warehouse", "billing") |
| **Email Config** | `smtpHost` | Text Input | SMTP server hostname |
| **Email Config** | `smtpPort` | Number Input | Default: 587 |
| **Email Config** | `username` | Email Input | SMTP authentication email |
| **Email Config** | `password` | Password Input | SMTP authentication password |
| **Email Config** | `fromEmail` | Email Input | Sender email address |
| **Email Config** | `fromName` | Text Input | Optional sender display name |
| **Email Config** | `useTLS` / `useSSL` / `authEnabled` | Toggle | Boolean flags |
| **Email Template** | `subject` | Text Input | Email subject line |
| **Email Template** | `body` | Textarea/HTML Editor | Email body (supports HTML) |
| **Email Template** | `ccEmails` / `bccEmails` | Multi-Email Input | List of email addresses |

---

## Endpoints Overview

| # | Method | Endpoint | Description |
|---|--------|----------|-------------|
| 1 | GET | `/tenant-settings/sla` | Get Task SLA Settings |
| 2 | PUT | `/tenant-settings/sla` | Update Task SLA Settings |
| 3 | GET | `/tenant-settings/email-configs` | Get Email Config List |
| 4 | POST | `/tenant-settings/email-configs` | Add Email Config |
| 5 | GET | `/tenant-settings/email-configs/{configKey}` | Get Email Config by Key |
| 6 | PUT | `/tenant-settings/email-configs/{configKey}` | Update Email Config |
| 7 | DELETE | `/tenant-settings/email-configs/{configKey}` | Delete Email Config |
| 8 | GET | `/tenant-settings/email-templates` | Get All Email Templates |
| 9 | GET | `/tenant-settings/email-templates/{templateType}` | Get Specific Email Template |
| 10 | PUT | `/tenant-settings/email-templates/{templateType}` | Set Email Template |

---

## 1. Get Task SLA Settings

**GET** `/api/v1/tenant-settings/sla`

Retrieves SLA (Service Level Agreement) settings for task operations.

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "slaSettings": {
      "countingSlaMinutes": 30,
      "transferSlaMinutes": 60,
      "offloadingSlaMinutes": 120,
      "receivingSlaMinutes": 180,
      "putawaySlaMinutes": 60,
      "pickingSlaMinutes": 90,
      "packMoveSlaMinutes": 60,
      "pickPackMoveSlaMinutes": 120,
      "loadingSlaMinutes": 90,
      "escalationAfterMinutes": 30
    },
    "lastModified": "2024-01-15T10:30:00"
  },
  "message": "SLA settings retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 2. Update Task SLA Settings

**PUT** `/api/v1/tenant-settings/sla`

Updates SLA settings for task operations. Supports **partial updates** - only provided fields will be updated.

### Request Body

```json
{
  "countingSlaMinutes": 45,
  "pickingSlaMinutes": 120
}
```

| Field | Type | Required | Validation | Description |
|-------|------|----------|------------|-------------|
| `countingSlaMinutes` | Integer | No | 1-10080 | SLA for counting tasks |
| `transferSlaMinutes` | Integer | No | 1-10080 | SLA for transfer tasks |
| `offloadingSlaMinutes` | Integer | No | 1-10080 | SLA for offloading tasks |
| `receivingSlaMinutes` | Integer | No | 1-10080 | SLA for receiving tasks |
| `putawaySlaMinutes` | Integer | No | 1-10080 | SLA for putaway tasks |
| `pickingSlaMinutes` | Integer | No | 1-10080 | SLA for picking tasks |
| `packMoveSlaMinutes` | Integer | No | 1-10080 | SLA for pack/move tasks |
| `pickPackMoveSlaMinutes` | Integer | No | 1-10080 | SLA for pick/pack/move tasks |
| `loadingSlaMinutes` | Integer | No | 1-10080 | SLA for loading tasks |
| `escalationAfterMinutes` | Integer | No | 1-10080 | Minutes before escalation |

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "countingSlaMinutes": 45,
    "transferSlaMinutes": 60,
    "offloadingSlaMinutes": 120,
    "receivingSlaMinutes": 180,
    "putawaySlaMinutes": 60,
    "pickingSlaMinutes": 120,
    "packMoveSlaMinutes": 60,
    "pickPackMoveSlaMinutes": 120,
    "loadingSlaMinutes": 90,
    "escalationAfterMinutes": 30
  },
  "message": "SLA settings updated successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 3. Get Email Config List

**GET** `/api/v1/tenant-settings/email-configs`

Retrieves all email configurations for the tenant.

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "configs": {
      "default": {
        "smtpHost": "smtp.gmail.com",
        "smtpPort": 587,
        "username": "noreply@company.com",
        "password": "****",
        "fromEmail": "noreply@company.com",
        "fromName": "WMS System",
        "useTLS": true,
        "useSSL": false,
        "authEnabled": true
      },
      "warehouse": {
        "smtpHost": "smtp.office365.com",
        "smtpPort": 587,
        "username": "warehouse@company.com",
        "password": "****",
        "fromEmail": "warehouse@company.com",
        "fromName": "Warehouse Operations",
        "useTLS": true,
        "useSSL": false,
        "authEnabled": true
      }
    },
    "count": 2,
    "lastModified": "2024-01-15T10:30:00"
  },
  "message": "Email configs retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 4. Add Email Config

**POST** `/api/v1/tenant-settings/email-configs`

Adds a new email configuration with a unique key.

### Request Body

```json
{
  "configKey": "billing",
  "smtpHost": "smtp.gmail.com",
  "smtpPort": 587,
  "username": "billing@company.com",
  "password": "app-password-here",
  "fromEmail": "billing@company.com",
  "fromName": "Billing Department",
  "useTLS": true,
  "useSSL": false,
  "authEnabled": true
}
```

| Field | Type | Required | Validation | Description |
|-------|------|----------|------------|-------------|
| `configKey` | String | Yes | NotBlank | Unique identifier for this config |
| `smtpHost` | String | Yes | NotBlank | SMTP server hostname |
| `smtpPort` | Integer | No | 1-65535 | SMTP port (default: 587) |
| `username` | String | Yes | Valid Email | SMTP authentication username |
| `password` | String | Yes | NotBlank | SMTP authentication password |
| `fromEmail` | String | Yes | Valid Email | Sender email address |
| `fromName` | String | No | - | Sender display name |
| `useTLS` | Boolean | No | - | Enable TLS (default: true) |
| `useSSL` | Boolean | No | - | Enable SSL (default: false) |
| `authEnabled` | Boolean | No | - | Enable authentication (default: true) |

### Response

**Status: 201 Created**

```json
{
  "success": true,
  "data": {
    "smtpHost": "smtp.gmail.com",
    "smtpPort": 587,
    "username": "billing@company.com",
    "password": "****",
    "fromEmail": "billing@company.com",
    "fromName": "Billing Department",
    "useTLS": true,
    "useSSL": false,
    "authEnabled": true
  },
  "message": "Email config added successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

### Error Response (Duplicate Key)

**Status: 409 Conflict**

```json
{
  "success": false,
  "error": {
    "message": "Email config with key 'billing' already exists. Use update endpoint instead."
  },
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 5. Get Email Config by Key

**GET** `/api/v1/tenant-settings/email-configs/{configKey}`

Retrieves a specific email configuration by its key.

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `configKey` | String | Yes | The email config key (e.g., "default", "warehouse") |

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "configKey": "default",
    "config": {
      "smtpHost": "smtp.gmail.com",
      "smtpPort": 587,
      "username": "noreply@company.com",
      "password": "****",
      "fromEmail": "noreply@company.com",
      "fromName": "WMS System",
      "useTLS": true,
      "useSSL": false,
      "authEnabled": true
    },
    "lastModified": "2024-01-15T10:30:00"
  },
  "message": "Email config retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 6. Update Email Config

**PUT** `/api/v1/tenant-settings/email-configs/{configKey}`

Updates an existing email configuration.

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `configKey` | String | Yes | The email config key to update |

### Request Body

Same structure as Add Email Config request.

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "smtpHost": "smtp.gmail.com",
    "smtpPort": 587,
    "username": "updated@company.com",
    "password": "****",
    "fromEmail": "updated@company.com",
    "fromName": "Updated Name",
    "useTLS": true,
    "useSSL": false,
    "authEnabled": true
  },
  "message": "Email config updated successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 7. Delete Email Config

**DELETE** `/api/v1/tenant-settings/email-configs/{configKey}`

Deletes an email configuration by its key.

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `configKey` | String | Yes | The email config key to delete |

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "deleted": true,
    "configKey": "billing"
  },
  "message": "Email config deleted successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 8. Get All Email Templates

**GET** `/api/v1/tenant-settings/email-templates`

Retrieves all email templates for the tenant.

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "templates": {
      "GRN": {
        "subject": "GRN Document - {{grnNumber}}",
        "body": "<html><body>Your GRN {{grnNumber}} has been processed...</body></html>",
        "emailConfigKey": "default",
        "ccEmails": ["supervisor@company.com"],
        "bccEmails": []
      },
      "GIN": null,
      "INVOICE": {
        "subject": "Invoice #{{invoiceNumber}}",
        "body": "<html><body>Please find attached invoice...</body></html>",
        "emailConfigKey": "billing",
        "ccEmails": [],
        "bccEmails": ["accounts@company.com"]
      },
      "PACKING_LIST": null,
      "DELIVERY_NOTE": null
    },
    "configuredCount": 2,
    "lastModified": "2024-01-15T10:30:00"
  },
  "message": "Email templates retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 9. Get Specific Email Template

**GET** `/api/v1/tenant-settings/email-templates/{templateType}`

Retrieves a specific email template by type.

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `templateType` | Enum | Yes | `GRN`, `GIN`, `INVOICE`, `PACKING_LIST`, `DELIVERY_NOTE` |

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "templateType": "GRN",
    "template": {
      "subject": "GRN Document - {{grnNumber}}",
      "body": "<html><body>Your GRN {{grnNumber}} has been processed...</body></html>",
      "emailConfigKey": "default",
      "ccEmails": ["supervisor@company.com"],
      "bccEmails": []
    },
    "isConfigured": true,
    "lastModified": "2024-01-15T10:30:00"
  },
  "message": "Email template retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

**Response (Not Configured)**

```json
{
  "success": true,
  "data": {
    "templateType": "GIN",
    "template": null,
    "isConfigured": false,
    "lastModified": "2024-01-15T10:30:00"
  },
  "message": "Email template retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## 10. Set Email Template

**PUT** `/api/v1/tenant-settings/email-templates/{templateType}`

Sets (creates or overwrites) an email template for a specific type.

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `templateType` | Enum | Yes | `GRN`, `GIN`, `INVOICE`, `PACKING_LIST`, `DELIVERY_NOTE` |

### Request Body

```json
{
  "subject": "GRN Document - {{grnNumber}}",
  "body": "<html><body><h1>GRN Confirmation</h1><p>Your GRN {{grnNumber}} has been processed on {{date}}.</p></body></html>",
  "emailConfigKey": "default",
  "ccEmails": ["supervisor@company.com", "manager@company.com"],
  "bccEmails": ["archive@company.com"]
}
```

| Field | Type | Required | Validation | Description |
|-------|------|----------|------------|-------------|
| `subject` | String | Yes | NotBlank | Email subject (supports placeholders) |
| `body` | String | Yes | NotBlank | Email body HTML (supports placeholders) |
| `emailConfigKey` | String | No | - | Reference to email config (default: "default") |
| `ccEmails` | List<String> | No | Valid Emails | CC recipients |
| `bccEmails` | List<String> | No | Valid Emails | BCC recipients |

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "subject": "GRN Document - {{grnNumber}}",
    "body": "<html><body><h1>GRN Confirmation</h1><p>Your GRN {{grnNumber}} has been processed on {{date}}.</p></body></html>",
    "emailConfigKey": "default",
    "ccEmails": ["supervisor@company.com", "manager@company.com"],
    "bccEmails": ["archive@company.com"]
  },
  "message": "Email template set successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

### Error Response (Invalid Config Key)

**Status: 400 Bad Request**

```json
{
  "success": false,
  "error": {
    "message": "Email config 'nonexistent' does not exist. Create it first or use 'default'."
  },
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## Enumerations

### EmailTemplateType

| Value | Description |
|-------|-------------|
| `GRN` | Goods Received Note email template |
| `GIN` | Goods Issue Note email template |
| `INVOICE` | Invoice email template |
| `PACKING_LIST` | Packing List email template |
| `DELIVERY_NOTE` | Delivery Note email template |

---

## Common Error Responses

### Tenant Context Missing

**Status: 403 Forbidden**

```json
{
  "success": false,
  "error": {
    "message": "Tenant context missing or invalid"
  },
  "timestamp": "2024-01-15T10:30:00"
}
```

### Resource Not Found

**Status: 404 Not Found**

```json
{
  "success": false,
  "error": {
    "message": "Email config not found with key: nonexistent"
  },
  "timestamp": "2024-01-15T10:30:00"
}
```

### Validation Error

**Status: 400 Bad Request**

```json
{
  "success": false,
  "error": {
    "message": "Validation failed",
    "details": [
      "smtpHost: SMTP host cannot be blank",
      "smtpPort: SMTP port must be positive"
    ]
  },
  "timestamp": "2024-01-15T10:30:00"
}
```
