package play.classloading;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.CompilationException;
import play.exceptions.UnexpectedException;

/**
 * Java compiler (uses eclipse JDT)
 */
public class ApplicationCompiler {

    Map<String, Boolean> packagesCache = new HashMap<String, Boolean>();
    ApplicationClasses applicationClasses;
	private CompilerOptions compilerOptions;

    /**
     * Try to guess the magic configuration options
     */
    public ApplicationCompiler(ApplicationClasses applicationClasses) {
    	Map<String, String> settings;
        this.applicationClasses = applicationClasses;
        settings = new HashMap<String, String>();
        settings.put(CompilerOptions.OPTION_ReportMissingSerialVersion, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_LineNumberAttribute, CompilerOptions.GENERATE);
        settings.put(CompilerOptions.OPTION_SourceFileAttribute, CompilerOptions.GENERATE);
        settings.put(CompilerOptions.OPTION_ReportDeprecation, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportUnusedImport, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_Encoding, "UTF-8");
        settings.put(CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.GENERATE);
        
        String javaVersion = CompilerOptions.VERSION_1_5;
        String javaVersionProperty = System.getProperty("java.version");
		if(javaVersionProperty.startsWith("1.6")) {
            javaVersion = CompilerOptions.VERSION_1_6;
        } else if (javaVersionProperty.startsWith("1.7")) {
            javaVersion = CompilerOptions.VERSION_1_7;
	    } else if (javaVersionProperty.startsWith("1.8")) {
	    	javaVersion = CompilerOptions.VERSION_1_8;
	    }
     
		if("1.5".equals(Play.configuration.get("java.source"))) {
            javaVersion = CompilerOptions.VERSION_1_5;
        } else if("1.6".equals(Play.configuration.get("java.source"))) {
            javaVersion = CompilerOptions.VERSION_1_6;
        } else if("1.7".equals(Play.configuration.get("java.source"))) {
            javaVersion = CompilerOptions.VERSION_1_7;
	    } else if("1.8".equals(Play.configuration.get("java.source"))) {
	    	javaVersion = CompilerOptions.VERSION_1_8;
	    }
		
        settings.put(CompilerOptions.OPTION_Source, javaVersion);
        settings.put(CompilerOptions.OPTION_TargetPlatform,  javaVersion);
        settings.put(CompilerOptions.OPTION_Compliance,  javaVersion);
        settings.put(CompilerOptions.PROTECTED,  javaVersion);
        settings.put(CompilerOptions.OPTION_PreserveUnusedLocal, CompilerOptions.PRESERVE);
        
        compilerOptions = new CompilerOptions(settings);
        compilerOptions.produceMethodParameters = true;
        compilerOptions.produceReferenceInfo = true;
        
    }

    /**
     * Something to compile
     */
    final class CompilationUnit implements ICompilationUnit {

        final private String clazzName;
        final private String fileName;
        final private char[] typeName;
        final private char[][] packageName;

        CompilationUnit(String pClazzName) {
            clazzName = pClazzName;
            if (pClazzName.contains("$")) {
                pClazzName = pClazzName.substring(0, pClazzName.indexOf("$"));
            }
            fileName = pClazzName.replace('.', '/') + ".java";
            int dot = pClazzName.lastIndexOf('.');
            if (dot > 0) {
                typeName = pClazzName.substring(dot + 1).toCharArray();
            } else {
                typeName = pClazzName.toCharArray();
            }
            StringTokenizer izer = new StringTokenizer(pClazzName, ".");
            packageName = new char[izer.countTokens() - 1][];
            for (int i = 0; i < packageName.length; i++) {
                packageName[i] = izer.nextToken().toCharArray();
            }
        }

        @Override
        public char[] getFileName() {
            return fileName.toCharArray();
        }

        @Override
        public char[] getContents() {
            return applicationClasses.getApplicationClass(clazzName).javaSource.toCharArray();
        }

        @Override
        public char[] getMainTypeName() {
            return typeName;
        }

        @Override
        public char[][] getPackageName() {
            return packageName;
        }

		/* (non-Javadoc)
		 * @see org.eclipse.jdt.internal.compiler.env.ICompilationUnit#ignoreOptionalProblems()
		 */
		@Override
		public boolean ignoreOptionalProblems() {
			// TODO Auto-generated method stub
			return false;
		}
    }

    /**
     * Please compile this className
     */
    @SuppressWarnings("deprecation")
    public void compile(String[] classNames) {

        ICompilationUnit[] compilationUnits = new CompilationUnit[classNames.length];
        for (int i = 0; i < classNames.length; i++) {
            compilationUnits[i] = new CompilationUnit(classNames[i]);
        }
        // bran get all the issues
        IErrorHandlingPolicy policy = DefaultErrorHandlingPolicies.exitAfterAllProblems();
//        IErrorHandlingPolicy policy = DefaultErrorHandlingPolicies.exitOnFirstError();
        IProblemFactory problemFactory = new DefaultProblemFactory(Locale.ENGLISH);

        /**
         * To find types ...
         */
        INameEnvironment nameEnvironment = new INameEnvironment() {

            @Override
            public NameEnvironmentAnswer findType(final char[][] compoundTypeName) {
                final StringBuilder result = new StringBuilder(compoundTypeName.length * 7);
                for (int i = 0; i < compoundTypeName.length; i++) {
                    if (i != 0) {
                        result.append('.');
                    }
                    result.append(compoundTypeName[i]);
                }
                return findType(result.toString());
            }

            @Override
            public NameEnvironmentAnswer findType(final char[] typeName, final char[][] packageName) {
                final StringBuilder result = new StringBuilder(packageName.length * 7 + 1 + typeName.length);
                for (int i = 0; i < packageName.length; i++) {
                    result.append(packageName[i]);
                    result.append('.');
                }
                result.append(typeName);
                return findType(result.toString());
            }

            private NameEnvironmentAnswer findType(final String name) {
                try {
                    if (name.startsWith("play.") || name.startsWith("java.") || name.startsWith("javax.")) {
                        byte[] bytes = Play.classloader.getClassDefinition(name);
                        if (bytes != null) {
                            ClassFileReader classFileReader = new ClassFileReader(bytes, name.toCharArray(), true);
                            return new NameEnvironmentAnswer(classFileReader, null);
                        }
                        return null;
                    }

                    char[] fileName = name.toCharArray();
                    ApplicationClass applicationClass = applicationClasses.getApplicationClass(name);

                    // ApplicationClass exists
                    if (applicationClass != null) {

                        if (applicationClass.javaByteCode != null) {
                            ClassFileReader classFileReader = new ClassFileReader(applicationClass.javaByteCode, fileName, true);
                            return new NameEnvironmentAnswer(classFileReader, null);
                        }
                        // Cascade compilation
                        ICompilationUnit compilationUnit = new CompilationUnit(name);
                        return new NameEnvironmentAnswer(compilationUnit, null);
                    }

                    // So it's a standard class
                    byte[] bytes = Play.classloader.getClassDefinition(name);
                    if (bytes != null) {
                        ClassFileReader classFileReader = new ClassFileReader(bytes, fileName, true);
                        return new NameEnvironmentAnswer(classFileReader, null);
                    }

                    // So it does not exist
                    return null;
                } catch (ClassFormatException e) {
                    // Something very very bad
                    throw new UnexpectedException(e);
                }
            }

            @Override
            public boolean isPackage(char[][] parentPackageName, char[] packageName) {
                // Rebuild something usable
                String name;
                if (parentPackageName == null) {
                    name = new String(packageName);
                }
                else {
                    StringBuilder sb = new StringBuilder(parentPackageName.length * 7 + packageName.length);
                    for (char[] p : parentPackageName) {
                        sb.append(p);
                        sb.append(".");
                    }
                    sb.append(new String(packageName));
                    name = sb.toString();
                }
                
                if (packagesCache.containsKey(name)) {
                    return packagesCache.get(name);
                }
                // Check if there are .java or .class for this resource
                if (Play.classloader.getResource(name.replace('.', '/') + ".class") != null) {
                    packagesCache.put(name, false);
                    return false;
                }
                if (applicationClasses.getApplicationClass(name) != null) {
                    packagesCache.put(name, false);
                    return false;
                }
                packagesCache.put(name, true);
                return true;
            }

            @Override
            public void cleanup() {
            }
        };

        /**
         * Compilation result
         */
        ICompilerRequestor compilerRequestor = new ICompilerRequestor() {

            @Override
            public void acceptResult(CompilationResult result) {
                // If error
                if (result.hasErrors()) {
                	// bran: sort the problems and report the first one
                    CategorizedProblem[] errors = result.getErrors();
                    Arrays.sort(errors, new Comparator<CategorizedProblem>() {
						@Override
						public int compare(CategorizedProblem o1,
								CategorizedProblem o2) {
							return o1.getSourceLineNumber() - o2.getSourceLineNumber();
						}
                    });
                    for (IProblem problem: errors) {
                        String className = new String(problem.getOriginatingFileName()).replace("/", ".");
                        className = className.substring(0, className.length() - 5);
                        String message = problem.getMessage();
                        if (problem.getID() == IProblem.CannotImportPackage) {
                            // Non sense !
                            message = problem.getArguments()[0] + " cannot be resolved";
                        }
                        throw new CompilationException(Play.classes.getApplicationClass(className).javaFile, message, problem.getSourceLineNumber(), problem.getSourceStart(), problem.getSourceEnd());
                    }
                }
                // Something has been compiled
                ClassFile[] clazzFiles = result.getClassFiles();
                for (int i = 0; i < clazzFiles.length; i++) {
                    final ClassFile clazzFile = clazzFiles[i];
                    final char[][] compoundName = clazzFile.getCompoundName();
                    final StringBuilder clazzName = new StringBuilder();
                    for (int j = 0; j < compoundName.length; j++) {
                        if (j != 0) {
                            clazzName.append('.');
                        }
                        clazzName.append(compoundName[j]);
                    }

                    if (Logger.isTraceEnabled()) {
                        Logger.trace("Compiled %s", clazzName);
                    }

                    applicationClasses.getApplicationClass(clazzName.toString()).compiled(clazzFile.getBytes());
                }
            }
        };

        /**
         * The JDT compiler
         */
        Compiler jdtCompiler = new Compiler(nameEnvironment, policy, compilerOptions, compilerRequestor, problemFactory) {

            @Override
            protected void handleInternalException(Throwable e, CompilationUnitDeclaration ud, CompilationResult result) {
            }
        };

        // Go !
        jdtCompiler.compile(compilationUnits);

    }
}
