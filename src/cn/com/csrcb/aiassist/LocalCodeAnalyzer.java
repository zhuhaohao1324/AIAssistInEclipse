package cn.com.csrcb.aiassist;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.List;

public class LocalCodeAnalyzer {

    private static final Pattern VAR_DECL_PATTERN = Pattern.compile(
        "(String|int|Integer|long|double|boolean|Map|List|Set|HashMap|ArrayList|Object|Date|\\w+)\\s+(\\w+)\\s*(=\\s*[^;]+)?");

    public static String analyze(String contextText, int cursorInContext) {
        if (contextText == null || contextText.isEmpty()) {
            return null;
        }

        int lineStart = findLineStart(contextText, cursorInContext);
        String currentLine = contextText.substring(lineStart, cursorInContext);
        String trimmed = currentLine.trim();

        if (trimmed.isEmpty()) {
            return null;
        }

        ContextVariables vars = extractContextVariables(contextText, lineStart);

        String suggestion = null;

        suggestion = matchIfStatement(trimmed, vars);
        if (suggestion != null) return suggestion;

        suggestion = matchForLoop(trimmed, vars);
        if (suggestion != null) return suggestion;

        suggestion = matchWhileLoop(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchTryStatement(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchSwitchStatement(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchMethodDefinition(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchConstructor(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchClassDefinition(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchOverride(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchMethodCall(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchObjectCreation(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchVariableDeclaration(trimmed, vars);
        if (suggestion != null) return suggestion;

        suggestion = matchReturn(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchThrow(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchLogger(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchBraceOpen(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchElseIf(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchDoWhile(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchNullCheck(trimmed, vars);
        if (suggestion != null) return suggestion;

        suggestion = matchMethodChain(trimmed, vars);
        if (suggestion != null) return suggestion;

        suggestion = matchSwitchEnhanced(trimmed, vars);
        if (suggestion != null) return suggestion;

        suggestion = matchEnhancedTry(trimmed, vars);
        if (suggestion != null) return suggestion;

        suggestion = matchQuickTemplate(trimmed, vars);
        if (suggestion != null) return suggestion;

        suggestion = matchStreamAPI(trimmed, vars);
        if (suggestion != null) return suggestion;

        suggestion = matchLambdaShortcut(trimmed, vars);
        if (suggestion != null) return suggestion;

        return null;
    }

    static class ContextVariables {
        List<String> strings = new ArrayList<>();
        List<String> ints = new ArrayList<>();
        List<String> bools = new ArrayList<>();
        List<String> objects = new ArrayList<>();
        List<String> maps = new ArrayList<>();
        List<String> lists = new ArrayList<>();
        List<String> collections = new ArrayList<>();

        String suggestCondition() {
            if (!strings.isEmpty()) {
                for (String s : strings) {
                    if (s.length() > 2) {
                        return "!" + s + ".isEmpty()";
                    }
                }
                return "!" + strings.get(0) + ".isEmpty()";
            }
            if (!objects.isEmpty()) {
                return objects.get(0) + " != null";
            }
            if (!bools.isEmpty()) {
                return bools.get(0);
            }
            if (!ints.isEmpty()) {
                return ints.get(0) + " > 0";
            }
            return "condition";
        }

        String getFirstObject() {
            if (!objects.isEmpty()) return objects.get(0);
            if (!lists.isEmpty()) return lists.get(0);
            if (!maps.isEmpty()) return maps.get(0);
            return "obj";
        }

        String getFirstList() {
            if (!lists.isEmpty()) return lists.get(0);
            if (!collections.isEmpty()) return collections.get(0);
            return "list";
        }
    }

    static ContextVariables extractContextVariables(String text, int lineStart) {
        ContextVariables vars = new ContextVariables();
        String beforeText = text.substring(0, lineStart);

        Matcher matcher = VAR_DECL_PATTERN.matcher(beforeText);
        while (matcher.find()) {
            String type = matcher.group(1);
            String varName = matcher.group(2);

            if (type.equals("String")) {
                vars.strings.add(varName);
            } else if (type.equals("int") || type.equals("Integer")) {
                vars.ints.add(varName);
            } else if (type.equals("boolean")) {
                vars.bools.add(varName);
            } else if (type.equals("Map") || type.equals("HashMap")) {
                vars.maps.add(varName);
            } else if (type.equals("List") || type.equals("ArrayList")) {
                vars.lists.add(varName);
            } else if (type.equals("Set") || type.equals("HashSet")) {
                vars.collections.add(varName);
            } else {
                vars.objects.add(varName);
            }
        }

        return vars;
    }

    private static int findLineStart(String text, int pos) {
        int start = pos;
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }

    private static String matchIfStatement(String line, ContextVariables vars) {
        String trimmed = line.trim();

        if (trimmed.equals("if") || trimmed.matches("if\\s*$")) {
            return " (" + vars.suggestCondition() + ") {\n    \n} else {\n    \n}";
        }
        if (trimmed.matches("if\\s*\\(\\s*$")) {
            return vars.suggestCondition() + ") {\n    \n} else {\n    \n}";
        }
        if (trimmed.matches("if\\s*\\(\\s*\\)\\s*$")) {
            return ") {\n    \n} else {\n    // TODO\n}";
        }
        if (trimmed.matches("if\\s*\\([^)]+\\)\\s*$")) {
            return ") {\n    \n} else {\n    \n}";
        }
        if (trimmed.matches("if\\s*\\([^)]+\\)\\s*\\{\\s*$")) {
            return "\n    // TODO\n} else {\n    // TODO\n}";
        }
        return null;
    }

    private static String matchForLoop(String line, ContextVariables vars) {
        String trimmed = line.trim();

        if (trimmed.equals("for") || trimmed.matches("for\\s*$")) {
            if (!vars.lists.isEmpty()) {
                return " (Object item : " + vars.lists.get(0) + ") {\n    \n}";
            }
            if (!vars.maps.isEmpty()) {
                return " (Map.Entry<String, Object> entry : " + vars.maps.get(0) + ".entrySet()) {\n    String key = entry.getKey();\n    Object value = entry.getValue();\n    \n}";
            }
            if (!vars.collections.isEmpty()) {
                return " (Object item : " + vars.collections.get(0) + ") {\n    \n}";
            }
            return " (int i = 0; i < size; i++) {\n    \n}";
        }
        if (trimmed.matches("for\\s*\\(\\s*$")) {
            return ") {\n    \n}";
        }
        if (trimmed.matches("for\\s*\\(.*\\)\\s*$")) {
            return ") {\n    \n}";
        }
        return null;
    }

    private static String matchWhileLoop(String line) {
        String trimmed = line.trim();

        if (trimmed.equals("while") || trimmed.matches("while\\s*$")) {
            return " (condition) {\n    \n}";
        }
        if (trimmed.matches("while\\s*\\(\\s*$")) {
            return ") {\n    \n}";
        }
        if (trimmed.matches("while\\s*\\([^)]+\\)\\s*$")) {
            return ") {\n    \n}";
        }
        return null;
    }

    private static String matchTryStatement(String line) {
        String trimmed = line.trim();

        if (trimmed.equals("try") || trimmed.matches("try\\s*$")) {
            return " {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}";
        }
        if (trimmed.matches("try\\s*\\(\\s*$")) {
            return ") {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}";
        }
        return null;
    }

    private static String matchSwitchStatement(String line) {
        String trimmed = line.trim();

        if (trimmed.equals("switch") || trimmed.matches("switch\\s*$")) {
            return " (value) {\n    case :\n        break;\n    default:\n        break;\n}";
        }
        if (trimmed.matches("switch\\s*\\([^)]+\\)\\s*$")) {
            return ") {\n    case :\n        break;\n    default:\n        break;\n}";
        }
        return null;
    }

    private static String matchMethodDefinition(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("public void \\w+\\s*$")) {
            return " () {\n    \n}";
        }
        if (trimmed.matches("public \\w+ \\w+\\s*$")) {
            return " () {\n    \n}";
        }
        if (trimmed.matches("private void \\w+\\s*$")) {
            return " () {\n    \n}";
        }
        if (trimmed.matches("\\w+ \\w+ *\\([^)]*\\)\\s*$")) {
            return " {\n    \n}";
        }
        return null;
    }

    private static String matchConstructor(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("public \\w+\\s*$")) {
            return " () {\n    \n}";
        }
        if (trimmed.matches("\\w+\\s*\\([^)]*\\)\\s*$")) {
            if (!trimmed.contains("void")) {
                return " {\n    \n}";
            }
        }
        return null;
    }

    private static String matchClassDefinition(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("class \\w+\\s*$")) {
            return " {\n    \n}";
        }
        return null;
    }

    private static String matchOverride(String line) {
        String trimmed = line.trim();

        if (trimmed.equals("@Override")) {
            return "\npublic void ";
        }
        return null;
    }
    
    private static String matchMethodCall(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("\\w+\\.\\s*$")) {
            return getCommonMethods(trimmed.replaceFirst("\\.\\s*$", ""));
        }
        if (trimmed.matches("\\w+\\.\\w+$")) {
            return "();";
        }
        if (trimmed.matches("\\w+\\.\\w+\\([^)]*$")) {
            return ");";
        }
        return null;
    }

    private static String getCommonMethods(String obj) {
        String lower = obj.toLowerCase();
        if ("list".equals(lower) || "arraylist".equals(lower)) {
            return "add();\nclear();\nget();\nisEmpty();\nremove();\nsize();";
        }
        if ("map".equals(lower) || "hashmap".equals(lower)) {
            return "get();\nput();\ncontainsKey();\nisEmpty();\nremove();\nsize();";
        }
        if ("set".equals(lower) || "hashset".equals(lower)) {
            return "add();\nclear();\ncontains();\nisEmpty();\nremove();\nsize();";
        }
        if ("string".equals(lower)) {
            return "trim();\nisEmpty();\nlength();\nsubstring();\ntoString();";
        }
        if ("logger".equals(lower) || "log".equals(lower)) {
            return "debug(\"\");\ninfo(\"\");\nwarn(\"\");\nerror(\"\");";
        }
        if ("sb".equals(lower) || "stringbuilder".equals(lower)) {
            return "append(\"\");\ntoString();\nlength();\ndelete();";
        }
        return "toString();\nequals();\nhashCode();";
    }

    private static String matchObjectCreation(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("new \\w+$")) {
            return trimmed.replaceFirst("new ", "") + "();";
        }
        if (trimmed.matches("new \\w+\\([^)]*$")) {
            return ");";
        }
        if (trimmed.matches("new ArrayList<>?\\s*$")) {
            return "();";
        }
        if (trimmed.matches("new HashMap<>?\\s*$")) {
            return "();";
        }
        return null;
    }

    private static String matchVariableDeclaration(String line, ContextVariables vars) {
        String trimmed = line.trim();

        String varName = trimmed.replaceFirst("^(String|int|Integer|long|double|boolean|Map|HashMap|List|ArrayList|Set|HashSet|Object|Date)\\s+", "");
        String lowerName = varName.toLowerCase();

        boolean isListVar = lowerName.contains("list") || lowerName.contains("array") || lowerName.contains("items");
        boolean isMapVar = lowerName.contains("map") || lowerName.contains("dict");
        boolean isSetVar = lowerName.contains("set");
        boolean isStrVar = lowerName.contains("name") || lowerName.contains("str") || lowerName.contains("text") || lowerName.contains("msg");
        boolean isBoolVar = lowerName.contains("flag") || lowerName.contains("valid") || lowerName.contains("enable") || lowerName.contains("active") || lowerName.startsWith("is") || lowerName.startsWith("has") || lowerName.startsWith("can");
        boolean isDateVar = lowerName.contains("date") || lowerName.contains("time") || lowerName.endsWith("at") || lowerName.endsWith("time");

        if (trimmed.matches("String\\s+\\w+$") || (isStrVar && !trimmed.contains("List"))) {
            return " = \"\";";
        }
        if (trimmed.matches("int\\s+\\w+$") || trimmed.matches("Integer\\s+\\w+$")) {
            return " = 0;";
        }
        if (trimmed.matches("long\\s+\\w+$")) {
            return " = 0L;";
        }
        if (trimmed.matches("double\\s+\\w+$")) {
            return " = 0.0;";
        }
        if (trimmed.matches("boolean\\s+\\w+$") || isBoolVar) {
            return " = false;";
        }
        if (trimmed.matches("Map<.*>\\s+\\w+$") || isMapVar) {
            return " = new HashMap<>();";
        }
        if (trimmed.matches("List<.*>\\s+\\w+$") || isListVar) {
            return " = new ArrayList<>();";
        }
        if (isSetVar) {
            return " = new HashSet<>();";
        }
        if (trimmed.matches("Date\\s+\\w+$") || isDateVar) {
            return " = new Date();";
        }
        if (trimmed.matches("Object\\s+\\w+$")) {
            return " = null;";
        }
        return null;
    }

    private static String matchReturn(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("return$") || trimmed.matches("return\\s+$")) {
            return " null;";
        }
        return null;
    }

    private static String matchThrow(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("throw new \\w+$")) {
            String ex = trimmed.replaceFirst("throw new ", "");
            return ex + "(\"\");";
        }
        if (trimmed.matches("throw new \\w+\\([^)]*$")) {
            return "\"\");";
        }
        return null;
    }

    private static String matchLogger(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("(LogUtil|Log)\\.\\s*$")) {
            return "debug(\"\");\ninfo(\"\");\nwarn(\"\");\nerror(\"\");";
        }
        if (trimmed.matches("(LogUtil|Log)\\.\\w+$")) {
            return "(\"\");";
        }
        return null;
    }

    private static String matchBraceOpen(String line) {
        String trimmed = line.trim();

        if (trimmed.equals("{") || trimmed.matches("\\{\\s*$")) {
            return "\n    \n}";
        }
        return null;
    }

    private static String matchElseIf(String line) {
        String trimmed = line.trim();

        if (trimmed.equals("elseif") || trimmed.equals("else if") || trimmed.matches("else if\\s*$")) {
            return " (condition) {\n    \n}";
        }
        return null;
    }

    private static String matchDoWhile(String line) {
        String trimmed = line.trim();

        if (trimmed.equals("do") || trimmed.matches("do\\s*$")) {
            return " {\n    \n} while (condition);";
        }
        return null;
    }

    private static String matchNullCheck(String line, ContextVariables vars) {
        String trimmed = line.trim();

        if (trimmed.equals("null") || trimmed.matches("null\\s*$")) {
            return "if (" + vars.getFirstObject() + " != null) {\n    \n}";
        }
        return null;
    }

    private static String matchMethodChain(String line, ContextVariables vars) {
        String trimmed = line.trim();

        if (trimmed.matches("\\w+\\.\\s*$")) {
            String objName = trimmed.replaceFirst("\\.\\s*$", "").trim();
            return getSmartMethodSuggestions(objName, vars);
        }
        return null;
    }

    private static String getSmartMethodSuggestions(String objName, ContextVariables vars) {
        StringBuilder suggestions = new StringBuilder();

        boolean isString = vars.strings.contains(objName);
        boolean isList = vars.lists.contains(objName);
        boolean isMap = vars.maps.contains(objName);
        boolean isCollection = vars.collections.contains(objName);

        if (isString || objName.toLowerCase().contains("str") || objName.toLowerCase().contains("name")) {
            suggestions.append("isEmpty();\nisBlank();\ntrim();\nlength();\nindexOf(\"\");\ncontains(\"\");");
        } else if (isList || objName.toLowerCase().contains("list")) {
            suggestions.append("add( );\nget(0);\nremove(0);\nclear();\nsize();\nisEmpty();\ncontains( );");
        } else if (isMap || objName.toLowerCase().contains("map") || objName.toLowerCase().contains("data")) {
            suggestions.append("get(\"\");\nput(\"\", );\ncontainsKey(\"\");\nkeySet();\nvalues();\nentrySet();");
        } else if (isCollection || objName.toLowerCase().contains("set")) {
            suggestions.append("add( );\ncontains( );\nremove( );\nsize();\nisEmpty();");
        } else {
            suggestions.append("toString();\nequals( );\nhashCode();");
        }

        return suggestions.toString();
    }

    private static String matchSwitchEnhanced(String line, ContextVariables vars) {
        String trimmed = line.trim();

        if (trimmed.equals("switch") || trimmed.matches("switch\\s*$")) {
            return " (" + vars.getFirstObject() + ") {\n    case :\n        break;\n    default:\n        break;\n}";
        }
        if (trimmed.matches("switch\\s*\\([^)]+\\)\\s*$")) {
            return ") {\n    case :\n        break;\n    default:\n        break;\n}";
        }
        return null;
    }

    private static String matchEnhancedTry(String line, ContextVariables vars) {
        String trimmed = line.trim();

        if (trimmed.equals("try") || trimmed.matches("try\\s*$")) {
            return " {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}";
        }
        if (trimmed.matches("try\\s*\\(\\s*$")) {
            return "BufferedReader br = new BufferedReader(new FileReader(\"\"))) {\n    \n} catch (IOException e) {\n    e.printStackTrace();\n}";
        }
        return null;
    }

    private static String matchQuickTemplate(String line, ContextVariables vars) {
        String trimmed = line.trim().toLowerCase();

        if (trimmed.equals("main")) {
            return " public static void main(String[] args) {\n    \n}";
        }
        if (trimmed.equals("sysout")) {
            return "System.out.println();";
        }
        if (trimmed.equals("singleton")) {
            return "private static final Singleton instance = new Singleton();\nprivate Singleton() {}\npublic static Singleton getInstance() {\n    return instance;\n}";
        }
        if (trimmed.equals("synchronized") || trimmed.equals("sync")) {
            return " (obj) {\n    \n}";
        }
        if (trimmed.equals("stream") || trimmed.equals("streamify")) {
            return vars.getFirstList() + ".stream()\n    .filter(e -> )\n    .map(e -> )\n    .collect(Collectors.toList());";
        }
        if (trimmed.matches("get\\w+") && !trimmed.equals("get")) {
            String fieldName = trimmed.substring(3).toLowerCase();
            if (!fieldName.isEmpty()) {
                String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                return " public Object get" + capitalized + "() {\n    return " + fieldName + ";\n}";
            }
        }
        if (trimmed.matches("set\\w+") && !trimmed.equals("set")) {
            String fieldName = trimmed.substring(3).toLowerCase();
            if (!fieldName.isEmpty()) {
                String capitalized = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
                return " public void set" + capitalized + "(Object " + fieldName + ") {\n    this." + fieldName + " = " + fieldName + ";\n}";
            }
        }
        return null;
    }

    private static String matchStreamAPI(String line, ContextVariables vars) {
        String trimmed = line.trim();

        if (trimmed.matches("\\w+\\.stream\\(\\)\\s*$")) {
            return ".filter(e -> )\n.map(e -> )\n.collect(Collectors.toList());";
        }
        if (trimmed.matches("\\.filter\\(\\s*$")) {
            return "e -> e.";
        }
        if (trimmed.matches("\\.map\\(\\s*$")) {
            return "e -> e.";
        }
        if (trimmed.matches("\\.forEach\\(\\s*$")) {
            return "e -> System.out.println(e);";
        }
        if (trimmed.matches("\\.collect\\(\\s*$")) {
            return "Collectors.toList());";
        }
        if (trimmed.matches("\\.sort\\(\\s*$")) {
            return "Comparator.comparing(e -> e.);";
        }
        return null;
    }

    private static String matchLambdaShortcut(String line, ContextVariables vars) {
        String trimmed = line.trim();

        if (trimmed.equals("->") || trimmed.matches("->\\s*$")) {
            return " {\n    \n}";
        }
        if (trimmed.equals("::") || trimmed.matches("::\\s*$")) {
            return "methodName;";
        }
        return null;
    }

    public static String getSupportedPatterns() {
        return "Supported patterns:\n" +
               "- if/else, null check shortcuts\n" +
               "- for/foreach loops (with context-aware variables)\n" +
               "- while/do-while loops\n" +
               "- try-catch blocks\n" +
               "- switch statements\n" +
               "- method definitions\n" +
               "- constructors\n" +
               "- class definitions\n" +
               "- @Override annotation\n" +
               "- method calls & chains\n" +
               "- object creation (new)\n" +
               "- variable declaration (smart inference)\n" +
               "- return/throw statements\n" +
               "- Logger calls\n" +
               "- Stream API shortcuts\n" +
               "- Lambda expressions\n" +
               "- Quick templates (main, singleton)\n" +
               "- getter/setter shortcuts\n" +
               "- brace completion";
    }
}