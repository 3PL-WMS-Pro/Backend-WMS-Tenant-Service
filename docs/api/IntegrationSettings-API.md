# Integration Settings API Specification

Base Path: `/api/v1/tenant-settings`

---

## Overview

Integration Settings APIs manage external service configurations for the tenant, including cloud storage (S3) settings. These settings are tenant-scoped and automatically use the authenticated tenant context.

---

## Frontend Implementation Notes

**Security Note:** Credentials (accessKey, secretKey) are **masked** in GET responses for security. The full credentials are only sent during SET operations.

**Form Field Types:**

| Field | Input Type | Notes |
|-------|------------|-------|
| `bucketName` | Text Input | **Required** - S3 bucket name |
| `region` | Dropdown | AWS region (default: `ap-south-1`) |
| `accessKey` | Text Input | **Required** - AWS Access Key ID |
| `secretKey` | Password Input | **Required** - AWS Secret Access Key |
| `bucketPrefix` | Text Input | Optional - Prefix for all objects in bucket |

**AWS Region Options:**
- `ap-south-1` (Mumbai)
- `ap-southeast-1` (Singapore)
- `ap-southeast-2` (Sydney)
- `us-east-1` (N. Virginia)
- `us-west-2` (Oregon)
- `eu-west-1` (Ireland)
- `eu-central-1` (Frankfurt)

---

## Endpoints Overview

| # | Method | Endpoint | Description |
|---|--------|----------|-------------|
| 1 | GET | `/tenant-settings/s3-config` | Get S3 Configuration |
| 2 | PUT | `/tenant-settings/s3-config` | Set S3 Configuration |

---

## 1. Get S3 Configuration

**GET** `/api/v1/tenant-settings/s3-config`

Retrieves S3 bucket configuration for the tenant. Credentials are **masked** for security (only last 4 characters shown).

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "bucketName": "wms-tenant-files-prod",
    "region": "ap-south-1",
    "accessKeyMasked": "****ABCD",
    "secretKeyMasked": "****wxyz",
    "bucketPrefix": "tenant-123/",
    "isConfigured": true,
    "lastModified": "2024-01-15T10:30:00"
  },
  "message": "S3 configuration retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `bucketName` | String | S3 bucket name |
| `region` | String | AWS region |
| `accessKeyMasked` | String | Masked access key (last 4 chars visible) |
| `secretKeyMasked` | String | Masked secret key (last 4 chars visible) |
| `bucketPrefix` | String | Optional prefix for objects |
| `isConfigured` | Boolean | Whether S3 is properly configured |
| `lastModified` | DateTime | Last modification timestamp |

---

## 2. Set S3 Configuration

**PUT** `/api/v1/tenant-settings/s3-config`

Sets (overwrites) S3 bucket configuration for the tenant. This completely replaces the existing S3 configuration.

### Request Body

```json
{
  "bucketName": "wms-tenant-files-prod",
  "region": "ap-south-1",
  "accessKey": "AKIAIOSFODNN7EXAMPLE",
  "secretKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "bucketPrefix": "tenant-123/"
}
```

### Request Fields

| Field | Type | Required | Validation | Description |
|-------|------|----------|------------|-------------|
| `bucketName` | String | Yes | NotBlank | S3 bucket name |
| `region` | String | Yes | NotBlank | AWS region (default: `ap-south-1`) |
| `accessKey` | String | Yes | NotBlank | AWS Access Key ID |
| `secretKey` | String | Yes | NotBlank | AWS Secret Access Key |
| `bucketPrefix` | String | No | - | Optional prefix for all objects |

### Response

**Status: 200 OK**

```json
{
  "success": true,
  "data": {
    "bucketName": "wms-tenant-files-prod",
    "region": "ap-south-1",
    "accessKeyMasked": "****MPLE",
    "secretKeyMasked": "****EKEY",
    "bucketPrefix": "tenant-123/",
    "isConfigured": true,
    "lastModified": "2024-01-15T10:35:00"
  },
  "message": "S3 configuration updated successfully",
  "timestamp": "2024-01-15T10:35:00"
}
```

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

### Tenant Not Found

**Status: 404 Not Found**

```json
{
  "success": false,
  "error": {
    "message": "Tenant not found with client ID: 999"
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
      "bucketName: Bucket name cannot be blank",
      "accessKey: Access key cannot be blank"
    ]
  },
  "timestamp": "2024-01-15T10:30:00"
}
```

---

## Usage Notes

1. **Credential Security**: Never display full credentials in the frontend. The GET endpoint returns masked values intentionally.

2. **Re-entering Credentials**: When updating S3 configuration, users must re-enter the full accessKey and secretKey, as masked values cannot be used.

3. **Bucket Prefix**: Use bucket prefix to organize files by tenant. Example: `tenant-123/` ensures all files are stored under that prefix.

4. **Testing Configuration**: After setting S3 configuration, it's recommended to test by uploading a small file to verify connectivity.
