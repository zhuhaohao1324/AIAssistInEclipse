package cn.com.csrcb.aiassist;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

/**
 * 基于 JDT AST 的代码结构分析器
 * 替代正则表达式，提供精确的代码结构解析
 */
public class ASTAnalyzer {

	/**
     * 方法信息
     */
    public static class MethodInfo {
        public String name;
        public String returnType;
        public String qualifiedReturnType;
        public List<VariableInfo> parameters = new ArrayList<>();
        public List<VariableInfo> localVariables = new ArrayList<>();
        public int startPosition;
        public int endPosition;
        public boolean isConstructor;
        public boolean isOverride;
        public List<String> annotations = new ArrayList<>();
        public int modifiers;

        public String getSignature() {
            StringBuilder sb = new StringBuilder();
            if (!annotations.isEmpty()) {
                sb.append("@").append(String.join(" @", annotations)).append(" ");
            }
            if (isConstructor) {
                sb.append(name).append("(");
            } else {
                if (Modifier.isStatic(modifiers)) sb.append("static ");
                if (Modifier.isFinal(modifiers)) sb.append("final ");
                sb.append(returnType).append(" ").append(name).append("(");
            }
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) sb.append(", ");
                VariableInfo p = parameters.get(i);
                sb.append(p.type).append(" ").append(p.name);
            }
            sb.append(")");
            return sb.toString();
        }
    }

    /**
     * 变量信息
     */
    public static class VariableInfo {
        public String name;
        public String type;
        public String qualifiedType;
        public boolean isField;
        public boolean isFinal;
        public boolean isStatic;
        public int modifiers;

        public String getDeclaration() {
            StringBuilder sb = new StringBuilder();
            if (isStatic) sb.append("static ");
            if (isFinal) sb.append("final ");
            sb.append(type).append(" ").append(name);
            return sb.toString();
        }
    }

    /**
     * 类型层次信息
     */
    public static class TypeHierarchy {
        public String superclass;
        public String qualifiedSuperclass;
        public List<String> interfaces = new ArrayList<>();
        public List<String> qualifiedInterfaces = new ArrayList<>();
    }
    /**
     * 类信息
     */
    public static class ClassInfo {
        public String name;
        public String qualifiedName;
        public List<MethodInfo> methods = new ArrayList<>();
        public List<VariableInfo> fields = new ArrayList<>();
        public TypeHierarchy typeHierarchy = new TypeHierarchy();
        public List<String> annotations = new ArrayList<>();
        public boolean isInterface;
        public boolean isEnum;
    }

    /**
     * 增强的上下文信息
     */
    public static class EnhancedContext {
        public String currentMethodSignature;
        public List<VariableInfo> availableVariables = new ArrayList<>();
        public ClassInfo currentClass = new ClassInfo();
        public List<String> classMethods = new ArrayList<>();
        public List<String> classFields = new ArrayList<>();
    }

    /**
     * 获取当前光标位置所在的方法
     */
    public static MethodInfo getCurrentMethod(ICompilationUnit cu, int offset) {
        CompilationUnit astRoot = parse(cu);
        final MethodInfo[] result = new MethodInfo[1];
        final boolean[] found = new boolean[1];

        astRoot.accept(new ASTVisitor() {
            private Stack<MethodDeclaration> methodStack = new Stack<>();

            @Override
            public boolean visit(MethodDeclaration node) {
                int nodeStart = node.getStartPosition();
                int nodeEnd = nodeStart + node.getLength();

                if (nodeStart <= offset && offset <= nodeEnd) {
                    methodStack.push(node);
                    MethodInfo info = convertMethod(node);
                    result[0] = info;
                    found[0] = true;
                    return true;
                }
                return false;
            }

            @Override
            public void endVisit(MethodDeclaration node) {
                if (!methodStack.isEmpty() && methodStack.peek() == node) {
                    methodStack.pop();
                }
            }
        });

        return result[0];
    }

    /**
     * 获取当前类的完整信息
     */
    public static ClassInfo getClassInfo(ICompilationUnit cu) {
        CompilationUnit astRoot = parse(cu);
        final ClassInfo[] result = new ClassInfo[1];

        astRoot.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                result[0] = convertClass(node, astRoot);
                return false;
            }
        });

        return result[0];
    }

    /**
     * 获取增强的上下文信息
     */
    public static EnhancedContext getEnhancedContext(ICompilationUnit cu, int offset) {
        EnhancedContext context = new EnhancedContext();
        CompilationUnit astRoot = parse(cu);

        astRoot.accept(new ASTVisitor() {
            private Stack<MethodDeclaration> methodStack = new Stack<>();

            @Override
            public boolean visit(MethodDeclaration node) {
                int nodeStart = node.getStartPosition();
                int nodeEnd = nodeStart + node.getLength();

                if (nodeStart <= offset && offset <= nodeEnd) {
                    methodStack.push(node);
                    MethodInfo methodInfo = convertMethod(node);
                    context.currentMethodSignature = methodInfo.getSignature();
                    context.availableVariables.addAll(methodInfo.parameters);
                    context.availableVariables.addAll(methodInfo.localVariables);
                }

                // 收集所有方法签名
                MethodInfo allMethodInfo = convertMethod(node);
                context.classMethods.add(allMethodInfo.getSignature());
                return true;
            }

            @Override
            public void endVisit(MethodDeclaration node) {
                if (!methodStack.isEmpty() && methodStack.peek() == node) {
                    methodStack.pop();
                }
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                for (Object frag : node.fragments()) {
                    VariableDeclarationFragment field = (VariableDeclarationFragment) frag;
                    VariableInfo varInfo = new VariableInfo();
                    varInfo.name = field.getName().getIdentifier();
                    varInfo.type = node.getType().toString();
                    varInfo.isField = true;
                    varInfo.isFinal = Modifier.isFinal(node.getModifiers());
                    varInfo.isStatic = Modifier.isStatic(node.getModifiers());
                    varInfo.modifiers = node.getModifiers();
                    context.classFields.add(varInfo.getDeclaration());
                }
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration node) {
                ClassInfo classInfo = convertClass(node, astRoot);
                context.currentClass = classInfo;
                return true;
            }
        });

        return context;
    }

    /**
     * 解析 CompilationUnit 为 AST
     */
    private static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.JLS8);
        parser.setSource(cu);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
    
    /**
     * 转换方法节点为 MethodInfo
     */
    private static MethodInfo convertMethod(MethodDeclaration node) {
        MethodInfo info = new MethodInfo();
        info.startPosition = node.getStartPosition();
        info.endPosition = info.startPosition + node.getLength();
        info.modifiers = node.getModifiers();
        info.isConstructor = node.isConstructor();

        IMethodBinding binding = node.resolveBinding();
        if (binding != null) {
            info.name = binding.getName();
            ITypeBinding returnType = binding.getReturnType();
            if (returnType != null) {
                info.returnType = returnType.getName();
                info.qualifiedReturnType = returnType.getQualifiedName();
            }

            // 解析参数
            ITypeBinding[] paramTypes = binding.getParameterTypes();
            for (ITypeBinding param : paramTypes) {
                VariableInfo varInfo = new VariableInfo();
                varInfo.type = param.getName();
                varInfo.qualifiedType = param.getQualifiedName();
                info.parameters.add(varInfo);
            }
        } else {
            info.name = node.getName().getIdentifier();
            Type returnTypeNode = node.getReturnType2();
            if (returnTypeNode != null) {
                info.returnType = returnTypeNode.toString();
            }
        }

        // 检查 @Override 注解
        for (Object modifier : node.modifiers()) {
            if (modifier instanceof Annotation) {
                Annotation ann = (Annotation) modifier;
                String annName = ann.getTypeName().getFullyQualifiedName();
                info.annotations.add(annName);
                if ("Override".equals(annName) || "java.lang.Override".equals(annName)) {
                    info.isOverride = true;
                }
            }
        }

        // 收集局部变量
        collectLocalVariables(node.getBody(), info);

        return info;
    }

    /**
     * 收集方法体内的局部变量
     */
    private static void collectLocalVariables(Block body, MethodInfo methodInfo) {
        if (body == null) return;

        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationStatement node) {
                Type typeNode = node.getType();
                for (Object frag : node.fragments()) {
                    VariableDeclarationFragment vdf = (VariableDeclarationFragment) frag;
                    VariableInfo varInfo = new VariableInfo();
                    varInfo.name = vdf.getName().getIdentifier();
                    varInfo.type = typeNode.toString();
                    varInfo.isFinal = Modifier.isFinal(node.getModifiers());
                    methodInfo.localVariables.add(varInfo);
                }
                return true;
            }
        });
    }
    /**
     * 转换类型节点为 ClassInfo
     */
    private static ClassInfo convertClass(TypeDeclaration node, CompilationUnit cu) {
        ClassInfo info = new ClassInfo();
        info.isInterface = node.isInterface();
        info.name = node.getName().getIdentifier();

        ITypeBinding binding = node.resolveBinding();
        if (binding != null) {
            info.isEnum = binding.isEnum();
            info.qualifiedName = binding.getQualifiedName();

            // 类型层次
            ITypeBinding superclass = binding.getSuperclass();
            if (superclass != null) {
                info.typeHierarchy.superclass = superclass.getName();
                info.typeHierarchy.qualifiedSuperclass = superclass.getQualifiedName();
            }

            for (ITypeBinding iface : binding.getInterfaces()) {
                info.typeHierarchy.interfaces.add(iface.getName());
                info.typeHierarchy.qualifiedInterfaces.add(iface.getQualifiedName());
            }
        }

        // 注解
        for (Object modifier : node.modifiers()) {
            if (modifier instanceof Annotation) {
                Annotation ann = (Annotation) modifier;
                info.annotations.add(ann.getTypeName().getFullyQualifiedName());
            }
        }

        // 字段
        for (FieldDeclaration field : node.getFields()) {
            for (Object frag : field.fragments()) {
                VariableDeclarationFragment vdf = (VariableDeclarationFragment) frag;
                VariableInfo varInfo = new VariableInfo();
                varInfo.name = vdf.getName().getIdentifier();
                varInfo.type = field.getType().toString();
                varInfo.isField = true;
                varInfo.isFinal = Modifier.isFinal(field.getModifiers());
                varInfo.isStatic = Modifier.isStatic(field.getModifiers());
                varInfo.modifiers = field.getModifiers();
                info.fields.add(varInfo);
            }
        }

        // 方法
        for (MethodDeclaration method : node.getMethods()) {
            info.methods.add(convertMethod(method));
        }

        return info;
    }

    /**
     * 生成增强的上下文描述（用于 Prompt）
     */
    public static String buildEnhancedContextDescription(ICompilationUnit cu, int offset) {
        EnhancedContext ctx = getEnhancedContext(cu, offset);
        StringBuilder sb = new StringBuilder();

        // 褰撳墠绫讳俊鎭�
        if (ctx.currentClass.name != null) {
            sb.append("// 当前类: ").append(ctx.currentClass.qualifiedName).append("\n");

            // 绫诲瀷灞傛
            if (ctx.currentClass.typeHierarchy.superclass != null) {
                sb.append("// 父类:").append(ctx.currentClass.typeHierarchy.qualifiedSuperclass).append("\n");
            }
            if (!ctx.currentClass.typeHierarchy.interfaces.isEmpty()) {
                sb.append("// 实现的接口:  ").append(String.join(", ", ctx.currentClass.typeHierarchy.qualifiedInterfaces)).append("\n");
            }
        }

        // 当前方法
        if (ctx.currentMethodSignature != null) {
            sb.append("// 当前方法: ").append(ctx.currentMethodSignature).append("\n");
        }

        // 可用变量
        if (!ctx.availableVariables.isEmpty()) {
            sb.append("// 可用变量:\\n");
            for (VariableInfo var : ctx.availableVariables) {
                sb.append("//   ").append(var.type).append(" ").append(var.name).append("\n");
            }
        }

     // 类字段
        if (!ctx.classFields.isEmpty()) {
            sb.append("// 类字段:\\n");
            for (String field : ctx.classFields) {
                sb.append("//   ").append(field).append("\n");
            }
        }

        // 类方法（不含构造器）
        if (!ctx.classMethods.isEmpty()) {
            sb.append("// 类方法:\\n");
            for (String method : ctx.classMethods) {
                if (!method.startsWith(ctx.currentClass.name + "(")) {
                    sb.append("//   ").append(method).append("\n");
                }
            }
        }

        return sb.toString();
    }
}
