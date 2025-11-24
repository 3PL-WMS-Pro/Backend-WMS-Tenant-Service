# User Role Mapping Management API Specification

Base Path: `/api/v1/user-role-mappings`

## Important: Field Naming Convention

Throughout this API, the following terms are **interchangeable** and refer to the same identifier:

| Entity | Equivalent Terms |
|--------|------------------|
| User Role Mapping | `userRoleCode`, `userRoleMappingId` |
| Role | `roleCode`, `roleType` |
| Warehouse | `warehouseId`, `warehouseCode` |
| User | `email` (primary identifier) |

These variations exist due to different naming conventions across request parameters, path variables, and response fields. They all represent the unique code/identifier of the entity.

---

## Frontend Implementation Notes

### Dropdown Fields

| Field | Input Type | Data Source API | Filter By | Display Value | Submit Value |
|-------|------------|-----------------|-----------|---------------|--------------|
| `roleCode` | Dropdown | `GET /api/v1/roles` | - | `roleName` | `roleCode` |
| `warehouses` | Multi-Select Dropdown | `GET /api/v1/warehouses` | - | `name` | `warehouseCode` |
| `currentWarehouse` | Dropdown | Local (from `warehouses` selection) | `warehouses` | `warehouseCode` | `warehouseCode` |

### References

- **Roles API**: Available in tenant service (role types: ADMIN, SUPERVISOR, OPERATOR, VIEWER)
- **Warehouses API**: See [Warehouse-API.md](../../../wms-warehouse-service/docs/api/Warehouse-API.md)

### Cascading Behavior

1. **Warehouses Selection**: User must first select one or more warehouses from the multi-select dropdown
2. **Current Warehouse Selection**: The `currentWarehouse` dropdown is populated only with warehouses selected in the `warehouses` field
3. If `currentWarehouse` is not explicitly set, it defaults to the first warehouse in the `warehouses` list

### Form Field Types

| Field | Input Type | Required | Notes |
|-------|------------|----------|-------|
| `email` | Text Input (Email) | Yes | Must be valid email format, auto-converted to lowercase |
| `roleCode` | Dropdown | Yes | Pattern: `ROLE-XXX` (e.g., ROLE-001) |
| `warehouses` | Multi-Select Dropdown | Yes | At least one warehouse must be selected |
| `currentWarehouse` | Dropdown | No | Must be one of the selected warehouses |
| `isActive` | Toggle/Checkbox | No | Defaults to `true` |
| `customPermissions` | Permission Checkboxes | No | Override specific role permissions |
| `createdBy` | Text Input (Hidden) | No | Auto-populated from auth context |

---

## Endpoints

### 1. Create User Role Mapping

**Endpoint:** `POST /api/v1/user-role-mappings`

Creates a new user role mapping, assigning permissions and warehouse access to a user within a tenant.

#### Request Body

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `email` | String | **Yes** | User's email address (must be valid email format) |
| `roleCode` | String | **Yes** | Role code to assign (pattern: `ROLE-XXX`) |
| `warehouses` | Array[String] | **Yes** | List of warehouse codes the user can access |
| `currentWarehouse` | String | No | Currently active warehouse (must be in warehouses list) |
| `customPermissions` | Map<String, Boolean> | No | Custom permission overrides |
| `isActive` | Boolean | No | Whether user is active (default: true) |
| `createdBy` | String | No | User who created this mapping |

#### Response

**Status:** `201 Created`

```json
{
  "success": true,
  "data": {
    "userRoleCode": "UR-001",
    "email": "user@example.com",
    "clientId": 1,
    "roleCode": "ROLE-001",
    "warehouses": ["WH1", "WH2"],
    "currentWarehouse": "WH1",
    "permissions": {
      "canOffload": true,
      "canReceive": true,
      "canPutaway": true,
      "canPick": true,
      "canPackMove": true,
      "canPickPackMove": true,
      "canLoad": true,
      "canCount": true,
      "canTransfer": true,
      "canAdjustInventory": true,
      "canViewReports": true,
      "canManageUsers": true,
      "canManageWarehouses": true,
      "canConfigureSettings": true,
      "canViewBilling": true,
      "canAccessApi": true,
      "canUseMobileApp": true,
      "canExportData": true
    },
    "customPermissions": {},
    "isActive": true,
    "createdBy": "admin@example.com",
    "lastLogin": null,
    "createdAt": "2024-01-15T10:30:00",
    "updatedAt": "2024-01-15T10:30:00"
  },
  "message": "User role mapping created successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

### 2. List User Role Mappings

**Endpoint:** `GET /api/v1/user-role-mappings`

Retrieves a paginated list of user role mappings for the current tenant with filtering options.

#### Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | Integer | No | 1 | Page number (1-indexed) |
| `limit` | Integer | No | 20 | Items per page (max: 100) |
| `roleType` | String | No | - | Filter by role code |
| `warehouse` | String | No | - | Filter by warehouse access |
| `isActive` | Boolean | No | - | Filter by active status |
| `search` | String | No | - | Search by email (case-insensitive) |
| `hasPermission` | String | No | - | Filter by permission (format: `permissionName=true`) |
| `sortBy` | String | No | - | Field to sort by |
| `sortOrder` | String | No | asc | Sort order: `asc` or `desc` |

#### Response

**Status:** `200 OK`

```json
{
  "success": true,
  "data": {
    "users": [
      {
        "email": "user@example.com",
        "roleType": "ROLE-001",
        "warehouses": ["WH1", "WH2"],
        "currentWarehouse": "WH1",
        "permissionsCount": 18,
        "isActive": true,
        "lastLogin": "2024-01-14T15:30:00",
        "createdAt": "2024-01-10T10:00:00"
      }
    ],
    "page": 1,
    "limit": 20,
    "totalItems": 50,
    "totalPages": 3,
    "hasNext": true,
    "hasPrevious": false,
    "summary": {
      "totalActive": 45,
      "totalInactive": 5,
      "rolesBreakdown": {
        "ROLE-001": 10,
        "ROLE-002": 20,
        "ROLE-003": 15,
        "ROLE-004": 5
      }
    }
  },
  "message": "User role mappings retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

### 3. Get Single User Role Mapping

**Endpoint:** `GET /api/v1/user-role-mappings/{email}`

Retrieves complete details of a specific user role mapping including all permissions.

#### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `email` | String | **Yes** | User's email address |

#### Response

**Status:** `200 OK`

```json
{
  "success": true,
  "data": {
    "email": "user@example.com",
    "roleType": {
      "roleType": "ROLE-001",
      "roleName": "Administrator",
      "description": "Full system access with all permissions"
    },
    "permissions": {
      "canOffload": true,
      "canReceive": true,
      "canPutaway": true,
      "canPick": true,
      "canPackMove": true,
      "canPickPackMove": true,
      "canLoad": true,
      "canCount": true,
      "canTransfer": true,
      "canAdjustInventory": true,
      "canViewReports": true,
      "canManageUsers": true,
      "canManageWarehouses": true,
      "canConfigureSettings": true,
      "canViewBilling": true,
      "canAccessApi": true,
      "canUseMobileApp": true,
      "canExportData": true,
      "canManageInventory": false,
      "canManageOrders": false,
      "canManageReports": false,
      "canViewAnalytics": false,
      "canAuditOperations": false,
      "isAdmin": false,
      "isSupervisor": false
    },
    "customPermissions": {
      "canViewBilling": false
    },
    "warehouses": [
      {
        "warehouseId": "WH1",
        "warehouseName": "Main Warehouse",
        "warehouseCode": "WH-WH1"
      },
      {
        "warehouseId": "WH2",
        "warehouseName": "Secondary Warehouse",
        "warehouseCode": "WH-WH2"
      }
    ],
    "currentWarehouse": {
      "warehouseId": "WH1",
      "warehouseName": "Main Warehouse",
      "warehouseCode": "WH-WH1"
    },
    "isActive": true,
    "lastLogin": "2024-01-14T15:30:00",
    "createdBy": "admin@example.com",
    "createdAt": "2024-01-10T10:00:00",
    "updatedAt": "2024-01-14T16:00:00",
    "permissionStats": {
      "grantedCount": 18,
      "operationalCount": 10,
      "managementCount": 5
    }
  },
  "message": "User role mapping retrieved successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

---

### 4. Update User Role Mapping

**Endpoint:** `PUT /api/v1/user-role-mappings/{email}`

Updates user role mapping including permissions, warehouses, and active status.

#### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `email` | String | **Yes** | User's email address |

#### Request Body

All fields are optional. Only provided fields will be updated.

| Field | Type | Description |
|-------|------|-------------|
| `roleType` | String | New role code to assign |
| `warehouses` | Array[String] | Updated list of warehouse codes |
| `currentWarehouse` | String | New current warehouse (must be in warehouses list) |
| `permissions` | Permissions | Updated permission settings |
| `customPermissions` | Map<String, Any> | Updated custom permission overrides |
| `isActive` | Boolean | Updated active status |

#### Response

**Status:** `200 OK`

```json
{
  "success": true,
  "data": {
    "email": "user@example.com",
    "roleType": "ROLE-002",
    "warehouses": ["WH1", "WH2", "WH3"],
    "currentWarehouse": "WH1",
    "permissions": {
      "canOffload": true,
      "canReceive": true,
      "canPutaway": true,
      "canPick": true,
      "canPackMove": true,
      "canPickPackMove": true,
      "canLoad": true,
      "canCount": true,
      "canTransfer": true,
      "canAdjustInventory": true,
      "canViewReports": true,
      "canManageUsers": false,
      "canManageWarehouses": false,
      "canConfigureSettings": false,
      "canViewBilling": false,
      "canAccessApi": true,
      "canUseMobileApp": true,
      "canExportData": true
    },
    "customPermissions": {},
    "isActive": true,
    "updatedAt": "2024-01-15T12:00:00",
    "changeSummary": {
      "fieldsModified": ["roleType", "warehouses"],
      "previousValues": {
        "roleType": "ROLE-001",
        "warehouses": ["WH1", "WH2"]
      },
      "newValues": {
        "roleType": "ROLE-002",
        "warehouses": ["WH1", "WH2", "WH3"]
      }
    }
  },
  "message": "User role mapping updated successfully",
  "timestamp": "2024-01-15T12:00:00"
}
```

---

### 5. Delete User Role Mapping

**Endpoint:** `DELETE /api/v1/user-role-mappings/{email}`

Soft deletes a user role mapping by setting `isActive` to false, maintaining audit trail.

#### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `email` | String | **Yes** | User's email address |

#### Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `hardDelete` | Boolean | No | false | If true, permanently deletes the record |

#### Request Headers

| Header | Type | Required | Description |
|--------|------|----------|-------------|
| `confirmationHeader` | String | Conditional | Required if `hardDelete=true`, must be `CONFIRM-DELETE` |

#### Response

**Status:** `200 OK`

```json
{
  "success": true,
  "data": {
    "email": "user@example.com",
    "deletionType": "soft",
    "deactivatedAt": "2024-01-15T14:00:00",
    "deactivatedBy": "admin@example.com",
    "message": "User deactivated successfully"
  },
  "message": "User role mapping deleted successfully",
  "timestamp": "2024-01-15T14:00:00"
}
```

---

## Data Models

### UserRoleMapping

| Field | Type | Description |
|-------|------|-------------|
| `userRoleCode` | String | Unique identifier (auto-generated, pattern: `UR-XXX`) |
| `email` | String | User's email address (lowercase, trimmed) |
| `clientId` | Integer | Tenant/client identifier |
| `roleCode` | String | Reference to role type (pattern: `ROLE-XXX`) |
| `warehouses` | Array[String] | List of warehouse codes user can access |
| `currentWarehouse` | String | Currently active warehouse |
| `permissions` | PermissionsSchema | Role-based permissions |
| `customPermissions` | Map<String, Boolean> | Custom permission overrides |
| `isActive` | Boolean | Whether user is active |
| `createdBy` | String | Creator identifier |
| `lastLogin` | LocalDateTime | Last login timestamp |
| `createdAt` | LocalDateTime | Creation timestamp |
| `updatedAt` | LocalDateTime | Last update timestamp |

### Permissions

| Field | Type | Default | Category | Description |
|-------|------|---------|----------|-------------|
| `canOffload` | Boolean | false | Operational - Inbound | Can perform offload operations |
| `canReceive` | Boolean | false | Operational - Inbound | Can perform receiving operations |
| `canPutaway` | Boolean | false | Operational - Inbound | Can perform putaway operations |
| `canPick` | Boolean | false | Operational - Outbound | Can perform picking operations |
| `canPackMove` | Boolean | false | Operational - Outbound | Can perform pack and move operations |
| `canPickPackMove` | Boolean | false | Operational - Outbound | Can perform pick, pack, and move operations |
| `canLoad` | Boolean | false | Operational - Outbound | Can perform loading operations |
| `canCount` | Boolean | false | Operational - Inventory | Can perform cycle counting |
| `canTransfer` | Boolean | false | Operational - Inventory | Can transfer inventory |
| `canAdjustInventory` | Boolean | false | Operational - Inventory | Can adjust inventory quantities |
| `canViewReports` | Boolean | false | Management | Can view reports |
| `canManageUsers` | Boolean | false | Management | Can manage user accounts |
| `canManageWarehouses` | Boolean | false | Management | Can manage warehouses |
| `canConfigureSettings` | Boolean | false | Management | Can configure system settings |
| `canViewBilling` | Boolean | false | Management | Can view billing information |
| `canAccessApi` | Boolean | true | System | Can access API |
| `canUseMobileApp` | Boolean | false | System | Can use mobile application |
| `canExportData` | Boolean | false | System | Can export data |

### Default Permissions by Role Type

| Role | Description | Key Permissions |
|------|-------------|-----------------|
| `ADMIN` | Full system access | All permissions enabled |
| `SUPERVISOR` | Supervisory access | All operational + view reports, export, analytics |
| `OPERATOR` | Warehouse operations | All operational permissions, mobile app access |
| `VIEWER` | Read-only access | View reports, analytics, API access only |

---

## Error Responses

### Standard Error Format

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable error message"
  },
  "timestamp": "2024-01-15T10:30:00"
}
```

### Error Codes

| HTTP Status | Code | Description |
|-------------|------|-------------|
| 400 | `VALIDATION_ERROR` | Invalid request data |
| 400 | `LAST_ADMIN_ERROR` | Cannot delete last admin user |
| 403 | `MISSING_TENANT_CONTEXT` | Tenant context not provided |
| 403 | `PERMISSION_DENIED` | Insufficient permissions |
| 403 | `INACTIVE_USER` | User account is inactive |
| 403 | `UNAUTHORIZED_WAREHOUSE` | Warehouse not in user's authorized list |
| 404 | `USER_NOT_FOUND` | User role mapping not found |
| 409 | `DUPLICATE_USER` | User role mapping already exists for email + client combination |
| 409 | `INACTIVE_WAREHOUSE` | Target warehouse is not operational |
| 500 | `INTERNAL_ERROR` | Internal server error |
