package cn.com.csrcb.aiassist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.jface.text.Document;

/**
 * Import 鑷姩绠＄悊鍣�
 * 鍒嗘瀽浠ｇ爜闇�瑕佺殑 import 骞惰嚜鍔ㄦ坊鍔�
 */
public class ImportManager {

    /** JDK 鍐呯疆绫诲瀷锛堜笉闇�瑕� import锛� */
    private static final Set<String> BUILTIN_TYPES = new HashSet<>();
    static {
        BUILTIN_TYPES.add("void");
        BUILTIN_TYPES.add("boolean");
        BUILTIN_TYPES.add("byte");
        BUILTIN_TYPES.add("char");
        BUILTIN_TYPES.add("short");
        BUILTIN_TYPES.add("int");
        BUILTIN_TYPES.add("long");
        BUILTIN_TYPES.add("float");
        BUILTIN_TYPES.add("double");
        BUILTIN_TYPES.add("String");  // java.lang.String 甯哥敤锛岃涓哄唴缃�
    }

    /** java.lang 鍖呬笅鐨勭被鍨嬶紙鑷姩瀵煎叆锛� */
    private static final String JAVA_LANG = "java.lang.";

    /** 绫诲瀷寮曠敤妯″紡锛氱敤浜庝粠浠ｇ爜涓彁鍙栫被鍨嬪悕 */
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "\\b([A-Z][a-zA-Z0-9_]*(\\.[A-Z][a-zA-Z0-9_]*)*)\\b");

    /**
     * 鍒嗘瀽琛ュ叏鍐呭闇�瑕佺殑 import
     * @param completionText AI 鐢熸垚鐨勮ˉ鍏ㄤ唬鐮�
     * @param cu 褰撳墠缂栬瘧鍗曞厓
     * @return 闇�瑕佺殑 import 鍒楄〃
     */
    public List<String> analyzeRequiredImports(String completionText, ICompilationUnit cu) {
        List<String> requiredImports = new ArrayList<>();

        if (completionText == null || completionText.isEmpty()) {
            return requiredImports;
        }

        Set<String> referencedTypes = extractReferencedTypes(completionText);

        for (String typeName : referencedTypes) {
            String importStatement = resolveImport(typeName, cu);
            if (importStatement != null && !isBuiltinType(typeName)) {
                requiredImports.add(importStatement);
            }
        }

        return requiredImports;
    }

    /**
     * 鍒嗘瀽闇�瑕佺殑 import锛堣繑鍥炵畝鍗曠被鍚嶅埌瀹屾暣鍖呭悕鐨勬槧灏勶級
     */
    public Map<String, String> analyzeImportMap(String completionText, ICompilationUnit cu) {
        Map<String, String> importMap = new HashMap<>();

        if (completionText == null || completionText.isEmpty()) {
            return importMap;
        }

        Set<String> referencedTypes = extractReferencedTypes(completionText);

        for (String typeName : referencedTypes) {
            String qualifiedName = resolveQualifiedName(typeName, cu);
            if (qualifiedName != null && !isBuiltinType(typeName)) {
                importMap.put(getSimpleName(qualifiedName), qualifiedName);
            }
        }

        return importMap;
    }

    /**
     * 浠庝唬鐮佷腑鎻愬彇寮曠敤鐨勭被鍨嬪悕
     */
    private Set<String> extractReferencedTypes(String code) {
        Set<String> types = new HashSet<>();
        Matcher matcher = TYPE_PATTERN.matcher(code);

        while (matcher.find()) {
            String type = matcher.group(1);
            // 杩囨护鎺変竴浜涙槑鏄句笉鏄被鍨嬬殑妯″紡
            if (isLikelyType(type)) {
                types.add(type);
            }
        }

        return types;
    }

    /**
     * 鍒ゆ柇鏄惁鍙兘鏄被鍨�
     */
    private boolean isLikelyType(String name) {
        if (name == null || name.isEmpty()) return false;

        // 鎺掗櫎鏂规硶璋冪敤锛堝悗闈㈡湁鎷彿锛�
        // 鎺掗櫎甯搁噺锛堝叏澶у啓锛�
        // 鎺掗櫎鍖呭悕锛堝寘鍚� impl, util, model 绛夊父瑙佸寘鍓嶇紑鐨勭煭鍚嶏級

        String simpleName = getSimpleName(name);

        // 鎺掗櫎鍏抽敭瀛楀拰甯歌闈炵被鍨嬫爣璇嗙
        if (simpleName.matches("\\d+") || simpleName.equals("return") ||
                simpleName.equals("if") || simpleName.equals("else") ||
                simpleName.equals("for") || simpleName.equals("while") ||
                simpleName.equals("switch") || simpleName.equals("case") ||
                simpleName.equals("new") || simpleName.equals("this") ||
                simpleName.equals("super") || simpleName.equals("class") ||
                simpleName.equals("interface") || simpleName.equals("enum") ||
                simpleName.equals("extends") || simpleName.equals("implements")) {
            return false;
        }

        // 绫诲瀷閫氬父浠ュぇ鍐欏瓧姣嶅紑澶�
        return Character.isUpperCase(simpleName.charAt(0));
    }

    /**
     * 瑙ｆ瀽绫诲瀷闇�瑕佺殑 import
     */
    private String resolveImport(String typeName, ICompilationUnit cu) {
        String qualifiedName = resolveQualifiedName(typeName, cu);
        if (qualifiedName == null) return null;

        // java.lang.* 涓嶉渶瑕� import
        if (qualifiedName.startsWith(JAVA_LANG)) {
            return null;
        }

        // 鑾峰彇褰撳墠鏂囦欢鐨勫寘鍚�
        String currentPackage = "";
        try {
            if (cu.getParent() instanceof org.eclipse.jdt.core.IPackageFragment) {
                currentPackage = ((org.eclipse.jdt.core.IPackageFragment) cu.getParent()).getElementName();
            }
        } catch (Exception e) {
            // ignore
        }

        // 鍚屽寘鐨勭被鍨嬩笉闇�瑕� import
        String typePackage = getPackageName(qualifiedName);
        if (typePackage.equals(currentPackage)) {
            return null;
        }

        return "import " + qualifiedName + ";";
    }

    /**
     * 瑙ｆ瀽绫诲瀷鐨勫畬鍏ㄩ檺瀹氬悕
     */
    private String resolveQualifiedName(String typeName, ICompilationUnit cu) {
        if (typeName == null) return null;

        // 宸茬粡鏄畬鍏ㄩ檺瀹氬悕
        if (typeName.contains(".")) {
            return typeName;
        }

        // 鍐呯疆绫诲瀷
        if (isBuiltinType(typeName)) {
            return typeName;
        }

        // java.lang 鍖�
        if (isJavaLangType(typeName)) {
            return JAVA_LANG + typeName;
        }

        try {
            IJavaProject project = cu.getJavaProject();

            // 1. 妫�鏌ユ槸鍚﹀凡鍦� import 涓�
            String existingImport = findExistingImport(typeName, cu);
            if (existingImport != null) {
                return existingImport;
            }

            // 2. 灏濊瘯浠庣储寮曡幏鍙�
            ProjectIndexer indexer = ProjectIndexer.getInstance();
            List<ProjectIndexer.TypeInfo> types = indexer.searchTypes(typeName);
            if (!types.isEmpty()) {
                // 杩斿洖绗竴涓尮閰嶏紙閫氬父鏄叕鍏辩被鍨嬶級
                return types.get(0).qualifiedName;
            }

            // 3. 灏濊瘯 JavaCore 瑙ｆ瀽
            IType type = project.findType(typeName);
            if (type != null) {
                return type.getFullyQualifiedName();
            }

            // 4. 灏濊瘯 java.lang 涓嬬殑绫诲瀷
            try {
                IType langType = project.findType("java.lang." + typeName);
                if (langType != null) {
                    return langType.getFullyQualifiedName();
                }
            } catch (Exception e) {
                // ignore
            }

            // 5. 灏濊瘯鍦ㄥ綋鍓嶅寘涓煡鎵�
            if (cu.getParent() instanceof org.eclipse.jdt.core.IPackageFragment) {
                org.eclipse.jdt.core.IPackageFragment pf =
                        (org.eclipse.jdt.core.IPackageFragment) cu.getParent();
                for (ICompilationUnit other : pf.getCompilationUnits()) {
                    IType primaryType = other.findPrimaryType();
                    if (primaryType != null && primaryType.getElementName().equals(typeName)) {
                        return primaryType.getFullyQualifiedName();
                    }
                }
            }

        } catch (JavaModelException e) {
            // ignore
        }

        return null;
    }

    /**
     * 鏌ユ壘宸叉湁鐨� import
     */
    private String findExistingImport(String simpleName, ICompilationUnit cu) {
        try {
            for (IImportDeclaration imp : cu.getImports()) {
                String name = imp.getElementName();
                if (name.endsWith("." + simpleName)) {
                    return name;
                }
            }
        } catch (JavaModelException e) {
            // ignore
        }
        return null;
    }

    /**
     * 妫�鏌ユ槸鍚︽槸鍐呯疆绫诲瀷
     */
    private boolean isBuiltinType(String typeName) {
        if (typeName == null) return false;
        return BUILTIN_TYPES.contains(typeName) || typeName.matches("^(byte|short|int|long|float|double|char|boolean|void)$");
    }

    /**
     * 妫�鏌ユ槸鍚︽槸 java.lang 鍖呬笅鐨勭被鍨�
     */
    private boolean isJavaLangType(String typeName) {
        if (typeName == null) return false;
        // 甯歌鐨� java.lang 绫诲瀷
        Set<String> langTypes = Set.of(
                "Object", "String", "Boolean", "Integer", "Long", "Double", "Float",
                "Byte", "Character", "Short", "Number", "Math", "System", "Thread",
                "Runnable", "Exception", "Error", "Throwable", "Class", "ClassLoader",
                "StringBuilder", "StringBuffer", "Comparable", "Cloneable", "Iterable",
                "Iterator", "Collection", "List", "Set", "Map", "ArrayList", "HashMap",
                "HashSet", "LinkedList", "Vector", "Stack", "Queue", "Deque",
                "Arrays", "Collections", "Objects", "Optional", "Stream", "Consumer",
                "Supplier", "Function", "Predicate", "Runnable", "Callable",
                "Future", "Executor", "ExecutorService", "ThreadPoolExecutor",
                "LocalDate", "LocalDateTime", "LocalTime", "Date", "Calendar",
                "SimpleDateFormat", "UUID", "BigDecimal", "BigInteger"
        );
        return langTypes.contains(typeName);
    }

    /**
     * 鑾峰彇绠�鍗曠被鍚�
     */
    private String getSimpleName(String qualifiedName) {
        if (qualifiedName == null) return null;
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }

    /**
     * 鑾峰彇鍖呭悕
     */
    private String getPackageName(String qualifiedName) {
        if (qualifiedName == null) return "";
        int lastDot = qualifiedName.lastIndexOf('.');
        // 澶勭悊鍐呴儴绫�
        if (lastDot > 0) {
            String potential = qualifiedName.substring(0, lastDot);
            // 妫�鏌ュ寘鍚嶆槸鍚﹀悎娉曪紙涓嶅寘鍚唴閮ㄧ被锛�
            if (potential.contains("$")) {
                return getPackageName(potential.replace('$', '.'));
            }
            return potential;
        }
        return "";
    }

    /**
     * 妫�鏌� import 鏄惁宸插瓨鍦�
     */
    public boolean hasImport(ICompilationUnit cu, String qualifiedName) {
        try {
            for (IImportDeclaration imp : cu.getImports()) {
                if (imp.getElementName().equals(qualifiedName)) {
                    return true;
                }
            }
        } catch (JavaModelException e) {
            // ignore
        }
        return false;
    }

    /**
     * 娣诲姞缂哄け鐨� import锛堜娇鐢� ASTRewrite锛�
     * @param cu 缂栬瘧鍗曞厓
     * @param imports 瑕佹坊鍔犵殑 import 鍒楄〃
     * @param monitor 杩涘害鐩戞帶
     * @return 缂栬緫缁撴灉锛屽鏋滄病鏈夋坊鍔犲垯杩斿洖 null
     * @throws IllegalArgumentException 
     * @throws JavaModelException 
     */
    public TextEdit addMissingImports(ICompilationUnit cu, List<String> imports) throws JavaModelException, IllegalArgumentException {
        if (cu == null || imports == null || imports.isEmpty()) {
            return null;
        }

        // 杩囨护宸插瓨鍦ㄧ殑 import
        List<String> toAdd = new ArrayList<>();
        for (String imp : imports) {
            if (imp != null && !imp.isEmpty()) {
                String qualifiedName = imp.replace("import ", "").replace(";", "").trim();
                if (!hasImport(cu, qualifiedName)) {
                    toAdd.add(qualifiedName);
                }
            }
        }

        if (toAdd.isEmpty()) {
            return null;
        }

        // 浣跨敤 AST 閲嶅啓娣诲姞 import
        CompilationUnit astRoot = parse(cu);
        ASTRewrite rewrite = ASTRewrite.create(astRoot.getAST());

        // 鎸夊寘鍚嶆帓搴忥紝浣� import 鏇存暣娲�
        toAdd.sort(String::compareTo);

        for (String qualifiedName : toAdd) {
            ImportDeclaration importDecl = astRoot.getAST().newImportDeclaration();
            Name name = astRoot.getAST().newName(qualifiedName.split("\\."));
            importDecl.setName(name);
            importDecl.setStatic(false);
            importDecl.setOnDemand(false);

            // 鎵惧埌姝ｇ‘鐨勬彃鍏ヤ綅缃紙鎸夊瓧姣嶉『搴忥級
            int insertIndex = findInsertIndex(astRoot, qualifiedName);
            rewrite.getListRewrite(astRoot, CompilationUnit.IMPORTS_PROPERTY)
                    .insertAt(importDecl, insertIndex, null);
        }

        return rewrite.rewriteAST();
    }

    /**
     * 瑙ｆ瀽 CompilationUnit
     */
    private CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.JLS8);
        parser.setSource(cu);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        return (CompilationUnit) parser.createAST(null);
    }

    /**
     * 鎵惧埌 import 鐨勬彃鍏ヤ綅缃紙淇濇寔鎸夊寘鍚嶆帓搴忥級
     */
    private int findInsertIndex(CompilationUnit cu, String qualifiedName) {
        int insertIndex = 0;
        boolean afterPackage = cu.getPackage() != null;

        for (Object obj : cu.imports()) {
            if (obj instanceof ImportDeclaration) {
                ImportDeclaration imp = (ImportDeclaration) obj;
                String existingName = imp.getName().getFullyQualifiedName();
                if (existingName.compareTo(qualifiedName) > 0) {
                    break;
                }
            }
            insertIndex++;
        }

        return insertIndex + (afterPackage ? 1 : 0);
    }

    /**
     * 鏍煎紡鍖� import 璇彞
     */
    public String formatImport(String qualifiedName) {
        if (qualifiedName == null) return null;
        if (qualifiedName.startsWith("import ") && qualifiedName.endsWith(";")) {
            return qualifiedName;
        }
        return "import " + qualifiedName + ";";
    }

    /**
     * 鐢熸垚 import 鍒楄〃鏂囨湰
     */
    public String generateImportText(List<String> imports) {
        if (imports == null || imports.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String imp : imports) {
            if (imp != null && !imp.isEmpty()) {
                if (!imp.startsWith("import ")) {
                    sb.append("import ").append(imp);
                    if (!imp.endsWith(";")) {
                        sb.append(";");
                    }
                } else {
                    sb.append(imp);
                    if (!imp.endsWith(";")) {
                        sb.append(";");
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
