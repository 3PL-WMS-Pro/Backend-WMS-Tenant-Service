package com.wmspro.tenant.exception

/**
 * Exception thrown when a requested resource is not found
 */
class NotFoundException(message: String) : RuntimeException(message)