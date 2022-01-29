package proguard.obfuscate;

import proguard.classfile.ClassPool;
import proguard.classfile.Clazz;
import proguard.classfile.LibraryClass;
import proguard.classfile.Member;
import proguard.classfile.ProgramClass;
import proguard.classfile.TypeConstants;
import proguard.classfile.kotlin.KotlinDeclarationContainerMetadata;
import proguard.classfile.kotlin.KotlinPropertyMetadata;
import proguard.classfile.kotlin.KotlinTypeAliasMetadata;
import proguard.classfile.kotlin.KotlinTypeMetadata;
import proguard.classfile.util.ClassUtil;
import proguard.classfile.visitor.ClassVisitor;
import proguard.resources.kotlinmodule.KotlinModule;
import proguard.util.FileNameParser;
import proguard.util.ListParser;
import proguard.util.StringMatcher;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NameObfuscator {
    // options
    private final DictionaryNameFactory classNameFactory;
    private final DictionaryNameFactory packageNameFactory;
    private final boolean useMixedCaseClassNames;
    private final StringMatcher keepPackageNamesMatcher;
    private final String flattenPackageHierarchy;
    private final String repackageClasses;
    private final boolean allowAccessModification;
    private final NameFactory nameFactory;

    public NameObfuscator(ClassPool programClassPool,
                          ClassPool libraryClassPool,
                          DictionaryNameFactory classNameFactory,
                          DictionaryNameFactory packageNameFactory,
                          boolean useMixedCaseClassNames,
                          List<String> keepPackageNames,
                          String flattenPackageHierarchy,
                          String repackageClasses,
                          boolean allowAccessModification, 
                          NameFactory nameFactory) {
        // First append the package separator if necessary.
        if (flattenPackageHierarchy != null &&
                flattenPackageHierarchy.length() > 0)
        {
            flattenPackageHierarchy += TypeConstants.PACKAGE_SEPARATOR;
        }

        // First append the package separator if necessary.
        if (repackageClasses != null && repackageClasses.length() > 0) {
            repackageClasses += TypeConstants.PACKAGE_SEPARATOR;
        }

        this.classNameFactory = classNameFactory;
        this.packageNameFactory = packageNameFactory;
        this.useMixedCaseClassNames = useMixedCaseClassNames;
        this.keepPackageNamesMatcher = keepPackageNames == null ? null :
                new ListParser(new FileNameParser()).parse(keepPackageNames);
        this.flattenPackageHierarchy = flattenPackageHierarchy;
        this.repackageClasses = repackageClasses;
        this.allowAccessModification = allowAccessModification;
        this.nameFactory = nameFactory;

        this.packagePrefixMap.put("", "");

        // Collect all names that have already been taken.
        MyKeepCollector collector = new MyKeepCollector();
        programClassPool.classesAccept(collector);
        libraryClassPool.classesAccept(collector);

    }

    private final Set<String> classNamesToAvoid = new HashSet<>();

    // Map: [package prefix - new package prefix]
    private final Map<String, String> packagePrefixMap = new HashMap<>();

    // Map: [package prefix - package name factory]
    private final Map<String, NameFactory> packagePrefixPackageNameFactoryMap = new HashMap<>();

    // Map: [package prefix - numeric class name factory]
    private final Map<String, NameFactory> packagePrefixClassNameFactoryMap = new HashMap<>();

    // Map: [package prefix - numeric class name factory]
    private final Map<String, NameFactory> packagePrefixNumericClassNameFactoryMap = new HashMap<>();

    public String generateInnerClassName(String parentClassName, @SuppressWarnings("unused") ProgramClass original) {
        return generateUniqueClassName(parentClassName + TypeConstants.INNER_CLASS_SEPARATOR);
    }

    public String generateClassName(ProgramClass original) {
        String newPackagePrefix = newPackagePrefix(ClassUtil.internalPackagePrefix(original.getName()));

        // Come up with a new class name, numeric or ordinary.
        return generateUniqueClassName(newPackagePrefix);
    }

    public String generateNumericClassName(String parentClassName, @SuppressWarnings("unused") ProgramClass original) {
        return generateUniqueNumericClassName(parentClassName);
    }

    @SuppressWarnings("unused")
    public String generateMemberName(Clazz clazz, Member member, Set<? super String> disallowedNames) {
        // Find an acceptable new name.
        nameFactory.reset();

        String newName;
        do {
            newName = nameFactory.nextName();
        } while (disallowedNames.contains(newName));

        return newName;
    }

    public void beginKotlinAliasNameScope() {
    }

    @SuppressWarnings("unused")
    public String generateKotlinAliasName(Clazz clazz,
                                          KotlinDeclarationContainerMetadata kotlinDeclarationContainerMetadata,
                                          KotlinTypeAliasMetadata kotlinTypeAliasMetadata,
                                          KotlinTypeMetadata kotlinTypeMetadata) {
        return nameFactory.nextName();
    }

    public void endKotlinAliasNameScope() {
    }

    public void beginKotlinModuleScope() {
    }

    @SuppressWarnings("unused")
    public String generateKotlinModuleName(KotlinModule kotlinModule) {
        return nameFactory.nextName();
    }

    public void endKotlinModuleScope() {
    }

    public void beginKotlinPropertyScope() {
    }

    @SuppressWarnings("unused")
    public String generateKotlinPropertyName(Clazz clazz,
                                           KotlinDeclarationContainerMetadata kotlinDeclarationContainerMetadata,
                                           KotlinPropertyMetadata kotlinPropertyMetadata) {
        return nameFactory.nextName();
    }

    public void endKotlinPropertyScope() {
    }

    /**
     * This ClassVisitor collects package names and class names that have to
     * be kept.
     */
    private class MyKeepCollector
            implements ClassVisitor
    {
        @Override
        public void visitAnyClass(Clazz clazz) { }


        @Override
        public void visitProgramClass(ProgramClass programClass)
        {
            // Does the program class already have a new name?
            String newClassName = ClassObfuscator.newClassName(programClass);
            if (newClassName != null)
            {
                // Remember not to use this name.
                classNamesToAvoid.add(mixedCaseClassName(newClassName));

                // Are we not aggressively repackaging all obfuscated classes?
                if (repackageClasses == null ||
                        !allowAccessModification)
                {
                    String className = programClass.getName();

                    // Keep the package name for all other classes in the same
                    // package. Do this recursively if we're not doing any
                    // repackaging.
                    mapPackageName(className,
                            newClassName,
                            repackageClasses        == null &&
                                    flattenPackageHierarchy == null);
                }
            }
        }


        public void visitLibraryClass(LibraryClass libraryClass)
        {
            // Get the new name or the original name of the library class.
            String newClassName = ClassObfuscator.newClassName(libraryClass);
            if (newClassName == null)
            {
                newClassName = libraryClass.getName();
            }

            // Remember not to use this name.
            classNamesToAvoid.add(mixedCaseClassName(newClassName));

            // Are we not aggressively repackaging all obfuscated classes?
            if (repackageClasses == null ||
                    !allowAccessModification)
            {
                String className = libraryClass.getName();

                // Keep the package name for all other classes in the same
                // package. Do this recursively if we're not doing any
                // repackaging.
                mapPackageName(className,
                        newClassName,
                        repackageClasses        == null &&
                                flattenPackageHierarchy == null);
            }
        }


        /**
         * Makes sure the package name of the given class will always be mapped
         * consistently with its new name.
         */
        private void mapPackageName(String  className,
                                    String  newClassName,
                                    boolean recursively)
        {
            String packagePrefix    = ClassUtil.internalPackagePrefix(className);
            String newPackagePrefix = ClassUtil.internalPackagePrefix(newClassName);

            // Put the mapping of this package prefix, and possibly of its
            // entire hierarchy, into the package prefix map.
            do
            {
                packagePrefixMap.put(packagePrefix, newPackagePrefix);

                if (!recursively)
                {
                    break;
                }

                packagePrefix    = ClassUtil.internalPackagePrefix(packagePrefix);
                newPackagePrefix = ClassUtil.internalPackagePrefix(newPackagePrefix);
            }
            while (packagePrefix.length()    > 0 &&
                    newPackagePrefix.length() > 0);
        }
    }

    /**
     * Finds or creates the new package prefix for the given package.
     */
    private String newPackagePrefix(String packagePrefix) {
        // Doesn't the package prefix have a new package prefix yet?
        String newPackagePrefix = packagePrefixMap.get(packagePrefix);
        if (newPackagePrefix == null) {
            // Are we keeping the package name?
            if (keepPackageNamesMatcher != null &&
                    keepPackageNamesMatcher.matches(packagePrefix.length() > 0 ?
                            packagePrefix.substring(0, packagePrefix.length() - 1) :
                            packagePrefix)) {
                return packagePrefix;
            }

            // Are we forcing a new package prefix?
            if (repackageClasses != null) {
                return repackageClasses;
            }

            // Are we forcing a new superpackage prefix?
            // Otherwise figure out the new superpackage prefix, recursively.
            String newSuperPackagePrefix = flattenPackageHierarchy != null ?
                    flattenPackageHierarchy :
                    newPackagePrefix(ClassUtil.internalPackagePrefix(packagePrefix));

            // Come up with a new package prefix.
            newPackagePrefix = generateUniquePackagePrefix(newSuperPackagePrefix);

            // Remember to use this mapping in the future.
            packagePrefixMap.put(packagePrefix, newPackagePrefix);
        }

        return newPackagePrefix;
    }


    /**
     * Creates a new package prefix in the given new superpackage.
     */
    private String generateUniquePackagePrefix(String newSuperPackagePrefix) {
        // Find the right name factory for this package.
        NameFactory packageNameFactory = packagePrefixPackageNameFactoryMap.get(newSuperPackagePrefix);
        if (packageNameFactory == null) {
            // We haven't seen packages in this superpackage before. Create
            // a new name factory for them.
            packageNameFactory = new SimpleNameFactory(useMixedCaseClassNames);
            if (this.packageNameFactory != null) {
                packageNameFactory =
                        new DictionaryNameFactory(this.packageNameFactory,
                                packageNameFactory);
            }

            packagePrefixPackageNameFactoryMap.put(newSuperPackagePrefix, packageNameFactory);
        }

        return generateUniquePackagePrefix(newSuperPackagePrefix, packageNameFactory);
    }


    /**
     * Creates a new package prefix in the given new superpackage, with the
     * given package name factory.
     */
    private String generateUniquePackagePrefix(String newSuperPackagePrefix,
                                               NameFactory packageNameFactory) {
        // Come up with package names until we get an original one.
        String newPackagePrefix;
        do {
            // Let the factory produce a package name.
            newPackagePrefix = newSuperPackagePrefix +
                    packageNameFactory.nextName() +
                    TypeConstants.PACKAGE_SEPARATOR;
        }
        while (packagePrefixMap.containsValue(newPackagePrefix));

        return newPackagePrefix;
    }

    /**
     * Creates a new class name in the given new package.
     */
    private String generateUniqueClassName(String newPackagePrefix) {
        // Find the right name factory for this package.
        NameFactory classNameFactory = packagePrefixClassNameFactoryMap.get(newPackagePrefix);
        if (classNameFactory == null) {
            // We haven't seen classes in this package before.
            // Create a new name factory for them.
            classNameFactory = new SimpleNameFactory(useMixedCaseClassNames);
            if (this.classNameFactory != null) {
                classNameFactory =
                        new DictionaryNameFactory(this.classNameFactory,
                                classNameFactory);
            }

            packagePrefixClassNameFactoryMap.put(newPackagePrefix,
                    classNameFactory);
        }

        return generateUniqueClassName(newPackagePrefix, classNameFactory);
    }

    /**
     * Creates a new class name in the given new package.
     */
    private String generateUniqueNumericClassName(String parentClassName) {
        String newPackagePrefix = parentClassName + TypeConstants.INNER_CLASS_SEPARATOR;
        // Find the right name factory for this package.
        NameFactory classNameFactory = packagePrefixNumericClassNameFactoryMap.get(newPackagePrefix);
        if (classNameFactory == null) {
            // We haven't seen classes in this package before.
            // Create a new name factory for them.
            classNameFactory = new NumericNameFactory();

            packagePrefixNumericClassNameFactoryMap.put(newPackagePrefix,
                    classNameFactory);
        }

        return generateUniqueClassName(newPackagePrefix, classNameFactory);
    }


    /**
     * Creates a new class name in the given new package, with the given
     * class name factory.
     */
    private String generateUniqueClassName(String newPackagePrefix,
                                           NameFactory classNameFactory) {
        // Come up with class names until we get an original one.
        String newClassName;
        String newMixedCaseClassName;
        do {
            // Let the factory produce a class name.
            newClassName = newPackagePrefix +
                    classNameFactory.nextName();

            newMixedCaseClassName = mixedCaseClassName(newClassName);
        }
        while (classNamesToAvoid.contains(newMixedCaseClassName));

        // Explicitly make sure the name isn't used again if we have a
        // user-specified dictionary and we're not allowed to have mixed case
        // class names -- just to protect against problematic dictionaries.
        if (this.classNameFactory != null &&
                !useMixedCaseClassNames) {
            classNamesToAvoid.add(newMixedCaseClassName);
        }

        return newClassName;
    }


    /**
     * Returns the given class name, unchanged if mixed-case class names are
     * allowed, or the lower-case version otherwise.
     */
    private String mixedCaseClassName(String className) {
        return useMixedCaseClassNames ?
                className :
                className.toLowerCase();
    }
}
