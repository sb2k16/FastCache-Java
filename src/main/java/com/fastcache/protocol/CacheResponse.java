package com.fastcache.protocol;

import java.util.Objects;

/**
 * Represents a response from the cache server to a client command.
 */
public class CacheResponse {
    private final ResponseStatus status;
    private final Object data;
    private final String errorMessage;
    private final long timestamp;
    
    public enum ResponseStatus {
        OK, ERROR, NOT_FOUND, INVALID_COMMAND, TIMEOUT
    }
    
    public CacheResponse(ResponseStatus status, Object data) {
        this(status, data, null);
    }
    
    public CacheResponse(ResponseStatus status, Object data, String errorMessage) {
        this.status = Objects.requireNonNull(status, "Status cannot be null");
        this.data = data;
        this.errorMessage = errorMessage;
        this.timestamp = System.currentTimeMillis();
    }
    
    public static CacheResponse ok(Object data) {
        return new CacheResponse(ResponseStatus.OK, data);
    }
    
    public static CacheResponse ok() {
        return new CacheResponse(ResponseStatus.OK, null);
    }
    
    public static CacheResponse error(String message) {
        return new CacheResponse(ResponseStatus.ERROR, null, message);
    }
    
    public static CacheResponse notFound() {
        return new CacheResponse(ResponseStatus.NOT_FOUND, null, "Key not found");
    }
    
    public static CacheResponse invalidCommand(String message) {
        return new CacheResponse(ResponseStatus.INVALID_COMMAND, null, message);
    }
    
    public static CacheResponse timeout() {
        return new CacheResponse(ResponseStatus.TIMEOUT, null, "Operation timed out");
    }
    
    public ResponseStatus getStatus() {
        return status;
    }
    
    public Object getData() {
        return data;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public boolean isSuccess() {
        return status == ResponseStatus.OK;
    }
    
    public boolean isError() {
        return status == ResponseStatus.ERROR;
    }
    
    public boolean isNotFound() {
        return status == ResponseStatus.NOT_FOUND;
    }
    
    public boolean isInvalidCommand() {
        return status == ResponseStatus.INVALID_COMMAND;
    }
    
    public boolean isTimeout() {
        return status == ResponseStatus.TIMEOUT;
    }
    
    public String getDataAsString() {
        return data != null ? data.toString() : null;
    }
    
    public Integer getDataAsInteger() {
        if (data instanceof Number) {
            return ((Number) data).intValue();
        }
        if (data instanceof String) {
            try {
                return Integer.parseInt((String) data);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    public Long getDataAsLong() {
        if (data instanceof Number) {
            return ((Number) data).longValue();
        }
        if (data instanceof String) {
            try {
                return Long.parseLong((String) data);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    public Boolean getDataAsBoolean() {
        if (data instanceof Boolean) {
            return (Boolean) data;
        }
        if (data instanceof String) {
            return Boolean.parseBoolean((String) data);
        }
        if (data instanceof Number) {
            return ((Number) data).intValue() != 0;
        }
        return null;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheResponse that = (CacheResponse) o;
        return timestamp == that.timestamp &&
                status == that.status &&
                Objects.equals(data, that.data) &&
                Objects.equals(errorMessage, that.errorMessage);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(status, data, errorMessage, timestamp);
    }
    
    @Override
    public String toString() {
        return "CacheResponse{" +
                "status=" + status +
                ", data=" + data +
                ", errorMessage='" + errorMessage + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
} 