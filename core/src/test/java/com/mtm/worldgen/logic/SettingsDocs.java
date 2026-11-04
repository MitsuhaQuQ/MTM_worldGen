package com.mtm.worldgen.logic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the documentation comments of {@link WorldgenSettings} from its source, as the text the config file shows
 * above each setting. A comment covers the field right after it; a group's comment, above its class, covers the
 * group. Run with the source and the resource file to write; the test checks the resource is up to date.
 */
public final class SettingsDocs {
    private static final Pattern GROUP_CLASS = Pattern.compile("^\\s*public static final class (\\w+) \\{");
    private static final Pattern GROUP_FIELD = Pattern.compile("^\\s*public final (\\w+) (\\w+) = new \\1\\(\\);");
    private static final Pattern FIELD = Pattern.compile("^\\s*public (?!static)(?:final )?[\\w<>\\[\\], .]+? (\\w+)\\s*=");
    private static final Pattern LINK = Pattern.compile("\\{@(?:link|code|linkplain) (?:[\\w.]*#)?([^}]*)}");

    private SettingsDocs() {
    }

    /** Documentation by key: a group's name, or group.field. In the order of the source. */
    public static Map<String, String> parse(List<String> source) {
        Map<String, String> classDocs = new HashMap<>();
        Map<String, String> fieldDocs = new LinkedHashMap<>();
        Map<String, String> groupOfClass = new LinkedHashMap<>();
        String pending = null;
        StringBuilder comment = null;
        String currentClass = null;
        int depth = 0;
        int classDepth = -1;
        for (String line : source) {
            String trimmed = line.trim();
            if (comment != null) {
                int end = trimmed.indexOf("*/");
                comment.append(' ').append(strip(end >= 0 ? trimmed.substring(0, end) : trimmed));
                if (end >= 0) {
                    pending = clean(comment.toString());
                    comment = null;
                }
                continue;
            }
            if (trimmed.startsWith("/**")) {
                String body = trimmed.substring(3);
                int end = body.indexOf("*/");
                if (end >= 0) {
                    pending = clean(body.substring(0, end));
                } else {
                    comment = new StringBuilder(strip(body));
                }
                continue;
            }
            Matcher group = GROUP_FIELD.matcher(line);
            if (group.find() && currentClass == null) {
                groupOfClass.put(group.group(1), group.group(2));
                pending = null;
            }
            Matcher type = GROUP_CLASS.matcher(line);
            if (type.find()) {
                currentClass = type.group(1);
                classDepth = depth;
                if (pending != null) {
                    classDocs.put(currentClass, pending);
                }
                pending = null;
            } else if (currentClass != null && depth == classDepth + 1) {
                Matcher field = FIELD.matcher(line);
                if (field.find()) {
                    String key = currentClass + "." + field.group(1);
                    if (pending != null) {
                        fieldDocs.put(key, pending);
                    } else {
                        fieldDocs.putIfAbsent(key, null);
                    }
                    pending = null;
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("@")) {
                    pending = null;
                }
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("@")) {
                pending = null;
            }
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (currentClass != null && depth == classDepth) {
                        currentClass = null;
                    }
                }
            }
        }
        Map<String, String> docs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : groupOfClass.entrySet()) {
            String className = entry.getKey();
            String groupName = entry.getValue();
            if (classDocs.containsKey(className)) {
                docs.put(groupName, classDocs.get(className));
            }
            for (Map.Entry<String, String> field : fieldDocs.entrySet()) {
                if (field.getKey().startsWith(className + ".") && field.getValue() != null) {
                    docs.put(groupName + field.getKey().substring(className.length()), field.getValue());
                }
            }
        }
        // Classes of nested objects, such as a climate zone's biomes, by their own name
        for (Map.Entry<String, String> field : fieldDocs.entrySet()) {
            String className = field.getKey().substring(0, field.getKey().indexOf('.'));
            if (!groupOfClass.containsKey(className) && field.getValue() != null) {
                docs.put(field.getKey(), field.getValue());
            }
        }
        return docs;
    }

    /** Fields of the source with no documentation of their own, as group.field. */
    public static List<String> undocumented(List<String> source) {
        Map<String, String> docs = parse(source);
        List<String> missing = new ArrayList<>();
        // Reparse to list every field, documented or not
        String currentClass = null;
        Map<String, String> groupOfClass = new HashMap<>();
        for (String line : source) {
            Matcher group = GROUP_FIELD.matcher(line);
            if (group.find()) {
                groupOfClass.put(group.group(1), group.group(2));
            }
        }
        int depth = 0;
        int classDepth = -1;
        for (String line : source) {
            Matcher type = GROUP_CLASS.matcher(line);
            if (type.find()) {
                currentClass = type.group(1);
                classDepth = depth;
            } else if (currentClass != null && depth == classDepth + 1) {
                Matcher field = FIELD.matcher(line);
                if (field.find() && groupOfClass.containsKey(currentClass)) {
                    String key = groupOfClass.get(currentClass) + "." + field.group(1);
                    if (!docs.containsKey(key)) {
                        missing.add(key);
                    }
                }
            }
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (currentClass != null && depth == classDepth) {
                        currentClass = null;
                    }
                }
            }
        }
        return missing;
    }

    /** The documentation as the resource stores it: one key=text line each. */
    public static String format(Map<String, String> docs) {
        StringBuilder out = new StringBuilder("# Generated from WorldgenSettings.java by SettingsDocs; edit the comments there.\n");
        for (Map.Entry<String, String> entry : docs.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue().replace("\\", "\\\\")).append('\n');
        }
        return out.toString();
    }

    private static String strip(String line) {
        String text = line.trim();
        return text.startsWith("*") ? text.substring(1).trim() : text;
    }

    private static String clean(String text) {
        String result = LINK.matcher(text).replaceAll("$1");
        result = result.replaceAll("<[^>]+>", " ");
        return result.replaceAll("\\s+", " ").trim();
    }

    public static void main(String[] args) throws IOException {
        List<String> source = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        if (args.length > 1) {
            Files.writeString(Path.of(args[1]), format(parse(source)), StandardCharsets.UTF_8);
        } else {
            System.out.println(format(parse(source)));
            System.out.println("UNDOCUMENTED " + undocumented(source));
        }
    }
}
