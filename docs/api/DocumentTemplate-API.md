# Document Template Management API Specification

Base Path: `/api/v1/document-templates`

## Important: Field Naming Convention

Throughout this API, the following terms are **interchangeable** and refer to the same identifier:

| Entity | Equivalent Terms |
|--------|------------------|
| Document Template | `templateId` |

These variations exist due to different naming conventions across request parameters, path variables, and response fields. They all represent the unique identifier of the entity.

---

## Frontend Implementation Notes

### Dropdown Fields

| Field | Input Type | Data Source API | Filter By | Display Value | Submit Value |
|-------|------------|-----------------|-----------|---------------|--------------|
| `documentType` | Dropdown | Static Enum | - | Enum label | Enum value |
| `pageSize` | Dropdown | Static Options | - | Size name | Size value |
| `orientation` | Dropdown | Static Options | - | Orientation name | Orientation value |

### References

- Document templates are managed centrally and can be global or tenant-specific
- Global templates (`tenantId = null`) serve as defaults for all tenants
- Tenant-specific templates override global defaults

### Form Field Types

| Field | Input Type | Notes |
|-------|------------|-------|
| `templateName` | Text Input | Required, free-form text |
| `documentType` | Dropdown | Required, options: `GRN`, `GIN`, `INVOICE`, `PACKING_LIST`, `DELIVERY_NOTE`, `STOCK_TRANSFER_NOTE` |
| `templateVersion` | Text Input | Default: "1.0" |
| `htmlTemplate` | Textarea/Code Editor | Required, HTML content with Thymeleaf syntax |
| `cssContent` | Textarea/Code Editor | Optional, CSS styling |
| `commonConfig.logoUrl` | Text Input | Optional, URL to logo image |
| `commonConfig.companyName` | Text Input | Optional, company name for branding |
| `commonConfig.primaryColor` | Color Picker | Default: "#000000" |
| `commonConfig.secondaryColor` | Color Picker | Default: "#666666" |
| `commonConfig.fontFamily` | Text Input | Default: "Arial, sans-serif" |
| `commonConfig.pageSize` | Dropdown | Options: `A4`, `Letter`, `Legal` |
| `commonConfig.orientation` | Dropdown | Options: `portrait`, `landscape` |
| `commonConfig.margins.top` | Text Input | Default: "20mm" |
| `commonConfig.margins.right` | Text Input | Default: "15mm" |
| `commonConfig.margins.bottom` | Text Input | Default: "20mm" |
| `commonConfig.margins.left` | Text Input | Default: "15mm" |
| `documentConfig` | JSON Editor | Optional, document-specific configuration |
| `isActive` | Toggle/Checkbox | Default: true |
| `isDefault` | Toggle/Checkbox | Default: false |

---

## Endpoints

### 1. Create Document Template

**Endpoint:** `POST /api/v1/document-templates`

Creates a new document template.

#### Request Body

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `templateName` | String | **Yes** | - | Name of the template |
| `documentType` | DocumentType | **Yes** | - | Type of document (GRN, GIN, etc.) |
| `templateVersion` | String | No | "1.0" | Version of the template |
| `htmlTemplate` | String | **Yes** | - | HTML template content (Thymeleaf syntax) |
| `cssContent` | String | No | null | CSS styling content |
| `commonConfig` | CommonConfig | No | defaults | Common configuration for branding and page settings |
| `documentConfig` | Map<String, Any> | No | {} | Document-specific configuration |
| `isActive` | Boolean | No | true | Whether the template is active |
| `isDefault` | Boolean | No | false | Whether this is the default template |

##### CommonConfig

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `logoUrl` | String | No | null | URL to logo image |
| `companyName` | String | No | null | Company name for branding |
| `primaryColor` | String | No | "#000000" | Primary color (hex code) |
| `secondaryColor` | String | No | "#666666" | Secondary color (hex code) |
| `fontFamily` | String | No | "Arial, sans-serif" | Font family |
| `pageSize` | String | No | "A4" | Page size (A4, Letter, Legal) |
| `orientation` | String | No | "portrait" | Page orientation (portrait, landscape) |
| `margins` | PageMargins | No | defaults | Page margins |

##### PageMargins

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `top` | String | No | "20mm" | Top margin |
| `right` | String | No | "15mm" | Right margin |
| `bottom` | String | No | "20mm" | Bottom margin |
| `left` | String | No | "15mm" | Left margin |

#### Response

**Status:** `201 Created`

```json
{
  "success": true,
  "data": {
    "templateId": "6745abc123def456",
    "documentType": "GRN",
    "templateName": "GRN Default Template",
    "templateVersion": "1.0",
    "htmlTemplate": "<html>...</html>",
    "cssContent": "body { font-family: Arial; }",
    "commonConfig": {
      "logoUrl": "https://example.com/logo.png",
      "companyName": "ACME Corp",
      "primaryColor": "#000000",
      "secondaryColor": "#666666",
      "fontFamily": "Arial, sans-serif",
      "pageSize": "A4",
      "orientation": "portrait",
      "margins": {
        "top": "20mm",
        "right": "15mm",
        "bottom": "20mm",
        "left": "15mm"
      }
    },
    "documentConfig": {
      "showVehicleNo": true,
      "showDriverName": true,
      "showCBM": true
    },
    "isActive": true,
    "isDefault": false,
    "createdAt": "2024-01-15T10:30:00",
    "updatedAt": "2024-01-15T10:30:00"
  },
  "message": "Template created successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

#### Validation Constraints

- `templateName`: Required, cannot be blank
- `htmlTemplate`: Required, cannot be blank
- Only one global default template allowed per document type

---

### 2. List Document Templates

**Endpoint:** `GET /api/v1/document-templates`

Retrieves all document templates.

#### Response

**Status:** `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "templateId": "6745abc123def456",
      "documentType": "GRN",
      "templateName": "GRN Default Template",
      "templateVersion": "1.0",
      "htmlTemplate": "<html>...</html>",
      "cssContent": "body { font-family: Arial; }",
      "commonConfig": { ... },
      "documentConfig": { ... },
      "isActive": true,
      "isDefault": true,
      "createdAt": "2024-01-15T10:30:00",
      "updatedAt": "2024-01-15T10:30:00"
    }
  ],
  "message": "All templates retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

### 3. Get Document Template by ID

**Endpoint:** `GET /api/v1/document-templates/{templateId}`

Retrieves a single document template by its ID.

#### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `templateId` | String | **Yes** | Unique template identifier |

#### Response

**Status:** `200 OK`

```json
{
  "success": true,
  "data": {
    "templateId": "6745abc123def456",
    "documentType": "GRN",
    "templateName": "GRN Default Template",
    "templateVersion": "1.0",
    "htmlTemplate": "<html>...</html>",
    "cssContent": "body { font-family: Arial; }",
    "commonConfig": {
      "logoUrl": "https://example.com/logo.png",
      "companyName": "ACME Corp",
      "primaryColor": "#000000",
      "secondaryColor": "#666666",
      "fontFamily": "Arial, sans-serif",
      "pageSize": "A4",
      "orientation": "portrait",
      "margins": {
        "top": "20mm",
        "right": "15mm",
        "bottom": "20mm",
        "left": "15mm"
      }
    },
    "documentConfig": {
      "showVehicleNo": true,
      "showDriverName": true
    },
    "isActive": true,
    "isDefault": true,
    "createdAt": "2024-01-15T10:30:00",
    "updatedAt": "2024-01-15T10:30:00"
  },
  "message": "Template retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

#### Error Response

**Status:** `404 Not Found`

```json
{
  "success": false,
  "data": null,
  "error": "Template not found with ID: 6745abc123def456",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

### 4. Update Document Template

**Endpoint:** `PUT /api/v1/document-templates/{templateId}`

Updates an existing document template.

#### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `templateId` | String | **Yes** | Unique template identifier |

#### Request Body

Same structure as Create Document Template request body.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `templateName` | String | **Yes** | Name of the template |
| `documentType` | DocumentType | **Yes** | Type of document |
| `templateVersion` | String | No | Version of the template |
| `htmlTemplate` | String | **Yes** | HTML template content |
| `cssContent` | String | No | CSS styling content |
| `commonConfig` | CommonConfig | No | Common configuration |
| `documentConfig` | Map<String, Any> | No | Document-specific configuration |
| `isActive` | Boolean | No | Whether the template is active |
| `isDefault` | Boolean | No | Whether this is the default template |

#### Response

**Status:** `200 OK`

```json
{
  "success": true,
  "data": {
    "templateId": "6745abc123def456",
    "documentType": "GRN",
    "templateName": "Updated GRN Template",
    "templateVersion": "1.1",
    "htmlTemplate": "<html>...</html>",
    "cssContent": "body { font-family: Helvetica; }",
    "commonConfig": { ... },
    "documentConfig": { ... },
    "isActive": true,
    "isDefault": true,
    "createdAt": "2024-01-15T10:30:00",
    "updatedAt": "2024-01-16T14:00:00"
  },
  "message": "Template updated successfully",
  "timestamp": "2024-01-16T14:00:00"
}
```

#### Validation Constraints

- Cannot set `isDefault = true` if another default template exists for the same document type

---

### 5. Delete Document Template

**Endpoint:** `DELETE /api/v1/document-templates/{templateId}`

Deletes a document template.

#### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `templateId` | String | **Yes** | Unique template identifier |

#### Response

**Status:** `200 OK`

```json
{
  "success": true,
  "data": "Template deleted successfully",
  "message": "Template deleted successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

#### Error Response (Default Template)

**Status:** `409 Conflict`

```json
{
  "success": false,
  "data": null,
  "error": "Cannot delete default template",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## Enumerations

### DocumentType

| Value | Description |
|-------|-------------|
| `GRN` | Goods Receipt Note |
| `GIN` | Goods Issue Note |
| `INVOICE` | Invoice document |
| `PACKING_LIST` | Packing list document |
| `DELIVERY_NOTE` | Delivery note document |
| `STOCK_TRANSFER_NOTE` | Stock transfer note document |

---

## Error Responses

All error responses follow a standard format:

| Field | Type | Description |
|-------|------|-------------|
| `success` | Boolean | Always `false` for errors |
| `data` | null | No data on error |
| `error` | String | Error message |
| `timestamp` | LocalDateTime | Response timestamp |

### Common Error Codes

| HTTP Status | Scenario |
|-------------|----------|
| `400 Bad Request` | Validation error (e.g., blank template name) |
| `404 Not Found` | Template not found |
| `409 Conflict` | State conflict (e.g., default template already exists, cannot delete default) |
| `500 Internal Server Error` | Unexpected server error |
