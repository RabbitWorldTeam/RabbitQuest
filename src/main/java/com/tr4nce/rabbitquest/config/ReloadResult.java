package com.tr4nce.rabbitquest.config;

/** Outcome of validating or publishing a candidate configuration reload. */
public record ReloadResult(boolean success, long version, String file, String field, String reason) {
    public static ReloadResult ok(long version) {
        return new ReloadResult(true, version, "", "", "");
    }

    public static ReloadResult failed(long currentVersion, String file, String field, String reason) {
        return new ReloadResult(false, currentVersion, file == null ? "" : file,
                field == null ? "" : field, reason == null ? "invalid" : reason);
    }

    public String summary() {
        if (success) {
            return "version=" + version;
        }
        StringBuilder sb = new StringBuilder();
        if (!file.isEmpty()) {
            sb.append(file);
        }
        if (!field.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append("[").append(field).append("]");
        }
        if (!reason.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(": ");
            }
            sb.append(reason);
        }
        return sb.toString();
    }
}
