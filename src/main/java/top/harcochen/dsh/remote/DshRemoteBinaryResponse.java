package top.harcochen.dsh.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Connection's multipart native-bytes envelope, narrowed to Workspace Files' data field. */
final class DshRemoteBinaryResponse {
    record FileBytes(JsonObject value, byte[] data) {}

    private DshRemoteBinaryResponse() {}

    static FileBytes parse(
            String contentType, byte[] body, String endpoint, String rpcId, int status) {
        if (status < 200
                || status >= 300
                || contentType == null
                || !contentType
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("multipart/form-data")) {
            DshRemoteContracts.parseUnaryResponse(
                    endpoint, new String(body, StandardCharsets.UTF_8), rpcId, status);
            throw invalid(endpoint);
        }
        Matcher boundaryMatch =
                Pattern.compile("(?:^|;)\\s*boundary=(?:\"([^\"]+)\"|([^;\\s]+))")
                        .matcher(contentType);
        if (!boundaryMatch.find()) throw invalid(endpoint);
        String boundary =
                boundaryMatch.group(1) == null ? boundaryMatch.group(2) : boundaryMatch.group(1);
        if (boundary.length() > 200 || boundary.contains("\r") || boundary.contains("\n"))
            throw invalid(endpoint);
        String wire = new String(body, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        int offset = 0;
        Map<String, byte[]> fields = new LinkedHashMap<>();
        while (wire.startsWith(delimiter, offset)) {
            offset += delimiter.length();
            if (wire.startsWith("--", offset)) {
                offset += 2;
                break;
            }
            if (!wire.startsWith("\r\n", offset)) throw invalid(endpoint);
            offset += 2;
            int headerEnd = wire.indexOf("\r\n\r\n", offset);
            if (headerEnd < 0 || headerEnd - offset > 8192) throw invalid(endpoint);
            String headers = wire.substring(offset, headerEnd);
            Matcher name =
                    Pattern.compile("(?im)^content-disposition:[^\\r\\n]*\\bname=\"([^\"]+)\"")
                            .matcher(headers);
            if (!name.find()) throw invalid(endpoint);
            int start = headerEnd + 4;
            int end = wire.indexOf("\r\n" + delimiter, start);
            if (end < 0
                    || fields.putIfAbsent(name.group(1), Arrays.copyOfRange(body, start, end))
                            != null) throw invalid(endpoint);
            offset = end + 2;
        }
        if (!(offset == wire.length() || wire.substring(offset).equals("\r\n")))
            throw invalid(endpoint);
        byte[] metadata = fields.remove("metadata");
        if (metadata == null) throw invalid(endpoint);
        JsonObject envelope;
        try {
            envelope =
                    JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8))
                            .getAsJsonObject();
        } catch (RuntimeException error) {
            throw invalid(endpoint);
        }
        if (!envelope.keySet().equals(Set.of("type", "rpcId", "result", "attachments"))
                || !envelope.get("attachments").isJsonArray()
                || envelope.getAsJsonArray("attachments").size() != 1) throw invalid(endpoint);
        JsonElement raw = envelope.getAsJsonArray("attachments").get(0);
        if (!raw.isJsonObject()) throw invalid(endpoint);
        JsonObject attachment = raw.getAsJsonObject();
        if (!attachment.keySet().equals(Set.of("path", "codec", "part"))
                || !"bytes".equals(top.harcochen.dsh.DshJson.string(attachment, "codec"))
                || !attachment.get("path").isJsonArray()
                || attachment.getAsJsonArray("path").size() != 1
                || !attachment.getAsJsonArray("path").get(0).isJsonPrimitive()
                || !"data".equals(attachment.getAsJsonArray("path").get(0).getAsString()))
            throw invalid(endpoint);
        String part = top.harcochen.dsh.DshJson.string(attachment, "part");
        if (part == null || !part.matches("bytes-[0-9]+")) throw invalid(endpoint);
        byte[] data = fields.remove(part);
        envelope.remove("attachments");
        JsonElement parsed =
                DshRemoteContracts.parseUnaryResponse(endpoint, envelope.toString(), rpcId, status);
        if (data == null
                || !fields.isEmpty()
                || !parsed.isJsonObject()
                || !parsed.getAsJsonObject().has("data")
                || !parsed.getAsJsonObject().get("data").isJsonNull()) throw invalid(endpoint);
        return new FileBytes(parsed.getAsJsonObject(), data);
    }

    private static DshRemoteException invalid(String endpoint) {
        return DshRemoteException.protocol(
                endpoint, "Invalid multipart Workspace Files response", null);
    }
}
