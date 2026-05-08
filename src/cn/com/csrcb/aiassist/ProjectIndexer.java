package cn.com.csrcb.aiassist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

/**
 * Project-level symbol indexer
 * Indexes all types and methods in the project for cross-file context awareness
 */
public class ProjectIndexer {

    private static ProjectIndexer instance;

    /** Singleton */
    public static synchronized ProjectIndexer getInstance() {
        if (instance == null) {
            instance = new ProjectIndexer();
        }
        return instance;
    }

    /** Type index cache: qualifiedName -> TypeInfo */
    private final Map<String, TypeInfo> typeIndex = new ConcurrentHashMap<>();

    /** Method index cache: typeName#methodName -> List<MethodInfo> */
    private final Map<String, List<MethodInfo>> methodIndex = new ConcurrentHashMap<>();

    /** Package name cache */
    private final Map<String, List<String>> packageIndex = new ConcurrentHashMap<>();

    /** Currently indexed project */
    private IJavaProject currentProject;

    /** Whether indexing is in progress */
    private volatile boolean indexing = false;

    /**
     * Type information
     */
    public static class TypeInfo {
        public String name;
        public String qualifiedName;
        public String packageName;
        public boolean isInterface;
        public boolean isEnum;
        public boolean isAnnotation;
        public String superclass;
        public List<String> interfaces = new ArrayList<>();
        public List<String> allSuperclasses = new ArrayList<>();

        public String getDisplayName() {
            if (packageName != null && !packageName.isEmpty()) {
                return qualifiedName;
            }
            return name;
        }
    }

    /**
     * Method information
     */
    public static class MethodInfo {
        public String declaringType;
        public String name;
        public String signature;
        public String returnType;
        public List<String> parameterTypes = new ArrayList<>();
        public boolean isStatic;
        public boolean isFinal;
        public int parameterCount;

        public String getFullSignature() {
            StringBuilder sb = new StringBuilder();
            if (isStatic) sb.append("static ");
            sb.append(returnType).append(" ").append(name).append("(");
            sb.append(parameterTypes.stream().collect(Collectors.joining(", ")));
            sb.append(")");
            return sb.toString();
        }
    }

    /**
     * Index current project (async)
     */
    public void indexProjectAsync(IJavaProject project) {
        if (indexing) {
            System.out.println("ProjectIndexer: Already indexing, skip");
            return;
        }

        if (project == currentProject && !typeIndex.isEmpty()) {
            System.out.println("ProjectIndexer: Project unchanged, use cache");
            return;
        }

        indexing = true;
        currentProject = project;

        Job indexJob = new Job("AI Assist: Indexing project...") {
            @Override
            protected org.eclipse.core.runtime.IStatus run(IProgressMonitor monitor) {
                try {
                    System.out.println("ProjectIndexer: Start indexing project " + project.getElementName());
                    long start = System.currentTimeMillis();

                    indexProjectSync(project, monitor);

                    long elapsed = System.currentTimeMillis() - start;
                    System.out.println("ProjectIndexer: Indexing complete, took " + elapsed + "ms");
                    System.out.println("ProjectIndexer: Indexed " + typeIndex.size() + " types");
                } catch (Exception e) {
                    System.err.println("ProjectIndexer: Indexing failed " + e.getMessage());
                } finally {
                    indexing = false;
                }
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
        };
        indexJob.setPriority(Job.BUILD);
        indexJob.schedule();
    }

    /**
     * Index project synchronously
     */
    public void indexProjectSync(IJavaProject project, IProgressMonitor monitor) {
        if (monitor == null) monitor = new NullProgressMonitor();

        typeIndex.clear();
        methodIndex.clear();
        packageIndex.clear();

        try {
            // 1. Index all types
            indexAllTypes(project, monitor);

            // 2. Index methods
            indexAllMethods(project, monitor);

        } catch (JavaModelException e) {
            System.err.println("ProjectIndexer: Indexing exception " + e.getMessage());
        }
    }

    /**
     * Index all types
     */
    private void indexAllTypes(IJavaProject project, IProgressMonitor monitor) throws JavaModelException {
        // Use SearchEngine to search all types
        SearchEngine engine = new SearchEngine();

        IJavaElement[] elements = new IJavaElement[] { project };
        boolean includePomContainers = false;
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(includePomContainers, elements, false);

        engine.searchAllTypeNames(null, null, scope, new TypeNameMatchRequestor() {
            @Override
            public void acceptTypeNameMatch(TypeNameMatch match) {
                TypeInfo info = new TypeInfo();
                info.qualifiedName = match.getFullyQualifiedName();
                info.packageName = match.getPackageName();

                // Extract simple name from qualified name
                String fqn = info.qualifiedName;
                int lastDot = fqn.lastIndexOf('.');
                info.name = lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;

                IType type = match.getType();
                if (type != null) {
                    try {
                        info.isInterface = type.isInterface();
                        info.isEnum = type.isEnum();
                        info.isAnnotation = type.isAnnotation();

                        // Superclass
                        ITypeHierarchy hierarchy = type.newSupertypeHierarchy(monitor);
                        IType[] superclasses = hierarchy.getAllSuperclasses(type);
                        if (superclasses.length > 0) {
                            info.superclass = superclasses[superclasses.length - 1].getFullyQualifiedName();
                            for (IType sc : hierarchy.getAllSuperclasses(type)) {
                                info.allSuperclasses.add(sc.getFullyQualifiedName());
                            }
                        }

                        // Interfaces
                        for (IType iface : hierarchy.getAllInterfaces()) {
                            info.interfaces.add(iface.getFullyQualifiedName());
                        }
                    } catch (JavaModelException e) {
                        // ignore
                    }
                }

                typeIndex.put(info.qualifiedName, info);

                // Package index
                packageIndex.computeIfAbsent(info.packageName, k -> new ArrayList<>())
                        .add(info.qualifiedName);
            }
        }, IJavaSearchConstants.CLASS, monitor);
    }

    /**
     * Index all methods
     */
    private void indexAllMethods(IJavaProject project, IProgressMonitor monitor) throws JavaModelException {
        for (IPackageFragmentRoot pfr : project.getPackageFragmentRoots()) {
            if (monitor.isCanceled()) break;

            if (pfr.getKind() == IPackageFragmentRoot.K_SOURCE) {
                for (Object child : pfr.getChildren()) {
                    IPackageFragment pf = (IPackageFragment) child;
                    for (ICompilationUnitWrapper cu : getCompilationUnits(pf)) {
                        indexMethodsInUnit(cu.getType(), monitor);
                    }
                }
            }
        }
    }

    /**
     * Simple wrapper for compilation unit (avoid direct dependency on ICompilationUnit)
     */
    private interface ICompilationUnitWrapper {
        IType getType() throws JavaModelException;
    }

    /**
     * Get compilation units in package
     */
    private List<ICompilationUnitWrapper> getCompilationUnits(IPackageFragment pf) throws JavaModelException {
        List<ICompilationUnitWrapper> result = new ArrayList<>();
        Object[] elements = pf.getCompilationUnits();
        for (Object elem : elements) {
            final org.eclipse.jdt.core.ICompilationUnit cu =
                    (org.eclipse.jdt.core.ICompilationUnit) elem;
            result.add(() -> cu.findPrimaryType());
        }
        return result;
    }

    /**
     * Index methods in a single compilation unit
     */
    private void indexMethodsInUnit(IType type, IProgressMonitor monitor) {
        if (type == null || monitor.isCanceled()) return;

        try {
            // Index methods of current type
            for (IMethod method : type.getMethods()) {
                String key = type.getFullyQualifiedName() + "#" + method.getElementName();

                MethodInfo info = new MethodInfo();
                info.declaringType = type.getFullyQualifiedName();
                info.name = method.getElementName();
                info.isStatic = org.eclipse.jdt.core.Flags.isStatic(method.getFlags());
                info.isFinal = org.eclipse.jdt.core.Flags.isFinal(method.getFlags());
                info.parameterCount = method.getNumberOfParameters();

                // Parameter types
                String[] paramTypes = method.getParameterTypes();
                Collections.addAll(info.parameterTypes, paramTypes);

                // Return type
                info.returnType = method.getReturnType();

                // Signature
                info.signature = info.returnType + " " + info.name + "(" +
                        String.join(", ", info.parameterTypes) + ")";

                methodIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(info);
            }

            // Recursively process inner classes
            for (IType innerType : type.getTypes()) {
                indexMethodsInUnit(innerType, monitor);
            }

        } catch (JavaModelException e) {
            // ignore
        }
    }

    /**
     * Search types (by prefix)
     */
    public List<TypeInfo> searchTypes(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>(typeIndex.values()).stream()
                    .limit(50)
                    .collect(Collectors.toList());
        }

        String lowerPrefix = prefix.toLowerCase();

        return typeIndex.values().stream()
                .filter(t -> t.name.toLowerCase().startsWith(lowerPrefix) ||
                        t.qualifiedName.toLowerCase().contains(lowerPrefix))
                .limit(20)
                .collect(Collectors.toList());
    }

    /**
     * Search methods
     */
    public List<MethodInfo> searchMethods(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return Collections.emptyList();
        }

        String lowerName = methodName.toLowerCase();

        List<MethodInfo> results = new ArrayList<>();
        for (Map.Entry<String, List<MethodInfo>> entry : methodIndex.entrySet()) {
            if (entry.getKey().toLowerCase().contains(lowerName)) {
                results.addAll(entry.getValue());
            }
        }

        return results;
    }

    /**
     * Search methods in a specific type
     */
    public List<MethodInfo> searchMethodsInType(String typeName, String methodName) {
        String key = typeName + "#" + methodName;
        return methodIndex.getOrDefault(key, Collections.emptyList());
    }

    /**
     * Get full type information
     */
    public TypeInfo getTypeInfo(String qualifiedName) {
        return typeIndex.get(qualifiedName);
    }

    /**
     * Get all types in a package
     */
    public List<TypeInfo> getTypesInPackage(String packageName) {
        List<String> names = packageIndex.get(packageName);
        if (names == null) return Collections.emptyList();

        return names.stream()
                .map(typeIndex::get)
                .filter(t -> t != null)
                .collect(Collectors.toList());
    }

    /**
     * Get all public types
     */
    public Collection<TypeInfo> getAllTypes() {
        return typeIndex.values();
    }

    /**
     * Check if type exists
     */
    public boolean hasType(String qualifiedName) {
        return typeIndex.containsKey(qualifiedName);
    }

    /**
     * Get method count
     */
    public int getMethodCount() {
        return methodIndex.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Get type count
     */
    public int getTypeCount() {
        return typeIndex.size();
    }

    /**
     * Check if indexing is in progress
     */
    public boolean isIndexing() {
        return indexing;
    }

    /**
     * Generate type suggestion list (for completion)
     */
    public List<String> generateTypeSuggestions(String prefix, int limit) {
        return searchTypes(prefix).stream()
                .limit(limit)
                .map(TypeInfo::getDisplayName)
                .collect(Collectors.toList());
    }

    /**
     * Generate method suggestion list (for completion)
     */
    public List<String> generateMethodSuggestions(String methodName, int limit) {
        return searchMethods(methodName).stream()
                .limit(limit)
                .map(MethodInfo::getFullSignature)
                .collect(Collectors.toList());
    }

    /**
     * Clear index
     */
    public void clear() {
        typeIndex.clear();
        methodIndex.clear();
        packageIndex.clear();
        currentProject = null;
    }
}
