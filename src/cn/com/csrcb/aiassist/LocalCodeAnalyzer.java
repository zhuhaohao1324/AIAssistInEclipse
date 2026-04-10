package cn.com.csrcb.aiassist;

/**
 * Based on regex matching local code completion analyzer
 * Provides intelligent completion suggestions based on code structure when AI is unavailable
 * 
 * No JDT dependency, pure string analysis, fast response
 */
public class LocalCodeAnalyzer {

    /**
     * Analyze context and generate completion suggestions
     * @param contextText Code context before and after cursor
     * @param cursorInContext Cursor position in context
     * @return Suggested code completion, or null if no completion available
     */
    public static String analyze(String contextText, int cursorInContext) {
        if (contextText == null || contextText.isEmpty()) {
            return null;
        }

        // Get current line
        int lineStart = getLineStart(contextText, cursorInContext);
        String currentLine = contextText.substring(lineStart, cursorInContext);
        String trimmed = currentLine.trim();

        if (trimmed.isEmpty()) {
            return null;
        }

        // Match code patterns by priority
        String suggestion = null;

        suggestion = matchIfStatement(trimmed);
        if (suggestion != null) return suggestion;

        suggestion = matchForLoop(trimmed);
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

        suggestion = matchVariableDeclaration(trimmed);
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

        return null;
    }

    private static String matchIfStatement(String line) {
        String trimmed = line.trim();

        // if 单独输入 -> 补全为 if (condition) { } else { }
        if (trimmed.equals("if") || trimmed.matches("if\\s*$")) {
            return " (condition) {\n    \n} else {\n    \n}";
        }
        // if( 或 if ( 缺少右括号 -> 补全右括号
        if (trimmed.matches("if\\s*\\(\\s*$")) {
            return ") {\n    \n} else {\n    \n}";
        }
        // if () 括号内为空 -> 补全条件
        if (trimmed.matches("if\\s*\\(\\s*\\)\\s*$")) {
            return ") {\n    // Code when condition is true\n} else {\n    // Code when condition is false\n}";
        }
        // if (something) -> 缺少右括号和代码块
        if (trimmed.matches("if\\s*\\([^)]+\\)\\s*$")) {
            return ") {\n    \n} else {\n    \n}";
        }
        // if (condition) { -> 缺少代码和结束大括号
        if (trimmed.matches("if\\s*\\([^)]+\\)\\s*\\{\\s*$")) {
            return "\n    // TODO\n} else {\n    // TODO\n}";
        }
        return null;
    }

    private static String matchForLoop(String line) {
        String trimmed = line.trim();

        // for 单独输入 -> 补全为 for (int i = 0; i < size; i++)
        if (trimmed.equals("for") || trimmed.matches("for\\s*$")) {
            return " (int i = 0; i < size; i++) {\n    \n}";
        }
        // for( 或 for ( -> 缺少右括号
        if (trimmed.matches("for\\s*\\(\\s*$")) {
            return ") {\n    \n}";
        }
        // for () -> 括号内为空
        if (trimmed.matches("for\\s*\\(\\s*\\)\\s*$")) {
            return " (int i = 0; i < size; i++) {\n    \n}";
        }
        // for (int i = 0; -> 缺少结束
        if (trimmed.matches("for\\s*\\(\\s*int\\s+\\w+\\s*=\\s*0\\s*;.*$")) {
            return "; i < list.size(); i++) {\n    Object item = list.get(i);\n    \n}";
        }
        // foreach: for (item : collection)
        if (trimmed.matches("for\\s*\\(\\s*\\w+\\s*:\\s*\\w+\\)\\s*$")) {
            return ") {\n    \n}";
        }
        // for (i = 0; i < 10; -> 缺少步进
        if (trimmed.matches("for\\s*\\([^;]+;\\s*\\)\\s*$")) {
            return "; i < length; i++) {\n    \n}";
        }
        // for (condition; -> 缺少条件判断和步进
        if (trimmed.matches("for\\s*\\([^;]+\\s*$")) {
            return "; i < size; i++) {\n    \n}";
        }
        return null;
    }

    private static String matchWhileLoop(String line) {
        String trimmed = line.trim();

        // while 单独输入
        if (trimmed.equals("while") || trimmed.matches("while\\s*$")) {
            return " (condition) {\n    \n}";
        }
        // while( 或 while (
        if (trimmed.matches("while\\s*\\(\\s*$")) {
            return ") {\n    \n}";
        }
        // while () 括号内为空
        if (trimmed.matches("while\\s*\\(\\s*\\)\\s*$")) {
            return " (condition) {\n    \n}";
        }
        // while (condition)
        if (trimmed.matches("while\\s*\\([^)]+\\)\\s*$")) {
            return ") {\n    \n}";
        }
        return null;
    }

    private static String matchTryStatement(String line) {
        String trimmed = line.trim();

        // try 单独输入
        if (trimmed.equals("try") || trimmed.matches("try\\s*$")) {
            return " {\n    // Code that may throw exception\n} catch (Exception e) {\n    e.printStackTrace();\n}";
        }
        // try {
        if (trimmed.matches("try\\s*\\{\\s*$")) {
            return "\n    // Code that may throw exception\n} catch (Exception e) {\n    e.printStackTrace();\n}";
        }
        return null;
    }

    private static String matchSwitchStatement(String line) {
        String trimmed = line.trim();

        // switch 单独输入
        if (trimmed.equals("switch") || trimmed.matches("switch\\s*$")) {
            return " (value) {\n    case :\n        break;\n    default:\n        break;\n}";
        }
        // switch(
        if (trimmed.matches("switch\\s*\\(\\s*$")) {
            return "value) {\n    case :\n        break;\n    default:\n        break;\n}";
        }
        // switch ()
        if (trimmed.matches("switch\\s*\\(\\s*\\)\\s*$")) {
            return " (value) {\n    case value1:\n        break;\n    case value2:\n        break;\n    default:\n        break;\n}";
        }
        // switch (value)
        if (trimmed.matches("switch\\s*\\([^)]+\\)\\s*$")) {
            return ") {\n    case :\n        break;\n    default:\n        break;\n}";
        }
        return null;
    }

    private static String matchMethodDefinition(String line) {
        String trimmed = line.trim();

        // void methodName()
        if (trimmed.matches("\\w+\\s+\\w+\\s*\\(\\s*\\)\\s*$")) {
            return " {\n    \n}";
        }
        // void methodName() {
        if (trimmed.matches("\\w+\\s+\\w+\\s*\\(\\s*\\)\\s*\\{\\s*$")) {
            return "\n    // TODO: Implement method\n}";
        }
        // ReturnType methodName(params)
        if (trimmed.matches("\\w+\\s+\\w+\\s*\\([^)]*\\)\\s*$") && !trimmed.contains("class ")) {
            return " {\n    \n}";
        }
        // ReturnType methodName(params) {
        if (trimmed.matches("\\w+\\s+\\w+\\s*\\([^)]*\\)\\s*\\{\\s*$")) {
            return "\n    // TODO\n}";
        }
        return null;
    }

    private static String matchConstructor(String line) {
        String trimmed = line.trim();

        if (!trimmed.startsWith("void ") && !trimmed.startsWith("int ") &&
            !trimmed.startsWith("String ") && !trimmed.startsWith("boolean ") &&
            !trimmed.startsWith("double ") && !trimmed.startsWith("long ") &&
            trimmed.matches("\\w+\\s*\\([^)]*\\)\\s*$")) {
            return " {\n    super();\n    // Initialization code\n}";
        }
        return null;
    }

    private static String matchClassDefinition(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("class\\s+\\w+\\s*$")) {
            return " {\n    // Member variables\n    // Constructors\n    // Member methods\n}";
        }
        if (trimmed.matches("class\\s+\\w+\\s+extends\\s+\\w+\\s*$")) {
            return " {\n    // Override parent methods\n}";
        }
        if (trimmed.matches("class\\s+\\w+\\s+implements\\s+\\w+\\s*$")) {
            return " {\n    // Implement interface methods\n}";
        }
        return null;
    }

    private static String matchOverride(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("@Override\\s*$")) {
            return "\npublic void methodName() {\n    super.methodName();\n}";
        }
        return null;
    }

    private static String matchMethodCall(String line) {
        String trimmed = line.trim();

        // obj.
        if (trimmed.matches("\\w+\\.\\s*$")) {
            return getCommonMethods(trimmed.replaceFirst("\\.\\s*$", ""));
        }
        // obj.method
        if (trimmed.matches("\\w+\\.\\w+$")) {
            return "();";
        }
        // obj.method(
        if (trimmed.matches("\\w+\\.\\w+\\([^)]*$")) {
            return ");";
        }
        return null;
    }

    private static String getCommonMethods(String obj) {
        String lower = obj.toLowerCase();
        switch (lower) {
            case "list":
            case "arraylist":
                return "add();\nclear();\nget();\nisEmpty();\nremove();\nsize();";
            case "map":
            case "hashmap":
                return "get();\nput();\ncontainsKey();\nisEmpty();\nremove();\nsize();";
            case "set":
            case "hashset":
                return "add();\nclear();\ncontains();\nisEmpty();\nremove();\nsize();";
            case "string":
                return "trim();\nisEmpty();\nlength();\nsubstring();\ntoString();";
            case "logger":
            case "log":
                return "debug(\"\");\ninfo(\"\");\nwarn(\"\");\nerror(\"\");";
            case "sb":
            case "stringbuilder":
                return "append(\"\");\ntoString();\nlength();\ndelete();";
            default:
                return "toString();\nequals();\nhashCode();";
        }
    }

    private static String matchObjectCreation(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("new\\s+\\w+$")) {
            String className = trimmed.replaceFirst("new\\s+", "");
            return className + "();";
        }
        if (trimmed.matches("new\\s+\\w+\\([^)]*$")) {
            return ");";
        }
        if (trimmed.matches("new\\s+ArrayList<>?\\s*$")) {
            return "();";
        }
        if (trimmed.matches("new\\s+HashMap<>?\\s*$")) {
            return "();";
        }
        return null;
    }

    private static String matchVariableDeclaration(String line) {
        String trimmed = line.trim();

        if (trimmed.matches("String\\s+\\w+$")) {
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
        if (trimmed.matches("boolean\\s+\\w+$")) {
            return " = false;";
        }
        // Map with generic
        if (trimmed.matches("Map<\\w+,\\s*\\w+>\\s+\\w+$")) {
            return " = new HashMap<>();";
        }
        // Map without generic
        if (trimmed.matches("Map\\s+\\w+$")) {
            return " = new HashMap<>();";
        }
        // List with generic
        if (trimmed.matches("List<\\w+>\\s+\\w+$")) {
            return " = new ArrayList<>();";
        }
        // List without generic
        if (trimmed.matches("List\\s+\\w+$")) {
            return " = new ArrayList<>();";
        }
        // Set with generic
        if (trimmed.matches("Set<\\w+>\\s+\\w+$")) {
            return " = new HashSet<>();";
        }
        // Set without generic
        if (trimmed.matches("Set\\s+\\w+$")) {
            return " = new HashSet<>();";
        }
        // HashMap with generic
        if (trimmed.matches("HashMap<\\w+,\\s*\\w+>\\s+\\w+$")) {
            return " = new HashMap<>();";
        }
        // HashMap without generic
        if (trimmed.matches("HashMap\\s+\\w+$")) {
            return " = new HashMap<>();";
        }
        // ArrayList
        if (trimmed.matches("ArrayList<\\w+>\\s+\\w+$")) {
            return " = new ArrayList<>();";
        }
        if (trimmed.matches("ArrayList\\s+\\w+$")) {
            return " = new ArrayList<>();";
        }
        // Object
        if (trimmed.matches("Object\\s+\\w+$")) {
            return " = null;";
        }
        // Date
        if (trimmed.matches("Date\\s+\\w+$")) {
            return " = new Date();";
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

        if (trimmed.matches("throw\\s+new\\s+\\w+$")) {
            String ex = trimmed.replaceFirst("throw\\s+new\\s+", "");
            return ex + "(\"\");";
        }
        if (trimmed.matches("throw\\s+new\\s+\\w+\\([^)]*$")) {
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

        if (trimmed.matches("\\{\\s*$")) {
            return "\n    // TODO\n}";
        }
        return null;
    }

    private static String matchElseIf(String line) {
        String trimmed = line.trim();

        // else 单独输入
        if (trimmed.equals("else") || trimmed.matches("else\\s*$")) {
            return " if (condition) {\n    \n} else {\n    \n}";
        }
        return null;
    }

    private static String matchDoWhile(String line) {
        String trimmed = line.trim();

        // do 单独输入
        if (trimmed.equals("do") || trimmed.matches("do\\s*$")) {
            return " {\n    // Loop body\n} while (condition);";
        }
        // do {
        if (trimmed.matches("do\\s*\\{\\s*$")) {
            return "\n    // Loop body\n} while (condition);";
        }
        return null;
    }

    private static int getLineStart(String text, int pos) {
        int start = pos;
        while (start > 0 && text.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }

    /**
     * Get supported analysis pattern list
     */
    public static String getSupportedPatterns() {
        return "Supported patterns:\n" +
               "- if/else statements\n" +
               "- for/foreach loops\n" +
               "- while/do-while loops\n" +
               "- try-catch blocks\n" +
               "- switch statements\n" +
               "- method definitions\n" +
               "- constructors\n" +
               "- class definitions\n" +
               "- @Override annotation\n" +
               "- method calls\n" +
               "- object creation (new)\n" +
               "- variable declaration and initialization\n" +
               "- return/throw statements\n" +
               "- Logger calls\n" +
               "- brace completion";
    }
}
