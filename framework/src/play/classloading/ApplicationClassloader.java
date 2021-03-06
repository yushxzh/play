package play.classloading;

import static org.apache.commons.io.IOUtils.closeQuietly;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassDefinition;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import play.Logger;
import play.Play;
import play.cache.Cache;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.hash.ClassStateHashCreator;
import play.exceptions.CompilationException;
import play.exceptions.UnexpectedException;
import play.libs.IO;
import play.vfs.VirtualFile;

/**
 * The application classLoader. 
 * Load the classes from the application Java sources files.
 */
public class ApplicationClassloader extends ClassLoader {


    private final ClassStateHashCreator classStateHashCreator = new ClassStateHashCreator();

    /**
     * A representation of the current state of the ApplicationClassloader.
     * It gets a new value each time the state of the classloader changes.
     */
    public ApplicationClassloaderState currentState = new ApplicationClassloaderState();

    /**
     * This protection domain applies to all loaded classes.
     */
    public ProtectionDomain protectionDomain;

    public ApplicationClassloader() {
        super(ApplicationClassloader.class.getClassLoader());
        // Clean the existing classes
        for (ApplicationClass applicationClass : Play.classes.all()) {
            applicationClass.uncompile();
        }
        pathHash = computePathHash();
        try {
            CodeSource codeSource = new CodeSource(new URL("file:" + Play.applicationPath.getAbsolutePath()), (Certificate[]) null);
            Permissions permissions = new Permissions();
            permissions.add(new AllPermission());
            protectionDomain = new ProtectionDomain(codeSource, permissions);
        } catch (MalformedURLException e) {
            throw new UnexpectedException(e);
        }
    }

    /**
     * You know ...
     */
    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> c = findLoadedClass(name);
        if (c != null) {
            return c;
        }

        // First check if it's an application Class
        Class<?> applicationClass = loadApplicationClass(name);
        if (applicationClass != null) {
            if (resolve) {
                resolveClass(applicationClass);
            }
            return applicationClass;
        }

        // Delegate to the classic classloader
        return super.loadClass(name, resolve);
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~
    public Class<?> loadApplicationClass(String name) {

        if (ApplicationClass.isClass(name)) {
            Class maybeAlreadyLoaded = findLoadedClass(name);
            if(maybeAlreadyLoaded != null) {
                return maybeAlreadyLoaded;
            }
        }

        if (Play.usePrecompiled) {
            try {
                File file = Play.getFile("precompiled/java/" + name.replace(".", "/") + ".class");
                if (!file.exists()) {
                    return null;
                }
                byte[] code = IO.readContent(file);
                Class<?> clazz = findLoadedClass(name);
                if (clazz == null) {
                    if (name.endsWith("package-info")) {
                        definePackage(getPackageName(name), null, null, null, null, null, null, null);
                    } else {
                        loadPackage(name);
                    }
                    clazz = defineClass(name, code, 0, code.length, protectionDomain);
                }
                ApplicationClass applicationClass = Play.classes.getApplicationClass(name);
                if (applicationClass != null) {
                    applicationClass.javaClass = clazz;
                    // bran: added. should do this to the enhancedBytecode too?
                    applicationClass.javaByteCode = code;
                    if (!applicationClass.isClass()) {
                        applicationClass.javaPackage = applicationClass.javaClass.getPackage();
                    }
                }
                return clazz;
            } catch (Exception e) {
                throw new RuntimeException("Cannot find precompiled class file for " + name);
            }
        }

        if(name.startsWith("java.") || name.startsWith("javax.") )
        	return null;
        
        long start = System.currentTimeMillis();
        ApplicationClass applicationClass = Play.classes.getApplicationClass(name);
        if (applicationClass != null) {
            if (applicationClass.isDefinable()) {
                return applicationClass.javaClass;
            }
            byte[] bc = BytecodeCache.getBytecode(name, applicationClass.javaSource);

            if (Logger.isTraceEnabled()) {
                Logger.trace("Compiling code for %s", name);
            }

            if (!applicationClass.isClass()) {
                definePackage(applicationClass.getPackage(), null, null, null, null, null, null, null);
            } else {
                loadPackage(name);
            }
            if (bc != null) {
                applicationClass.enhancedByteCode = bc;
                applicationClass.javaClass = defineClass(applicationClass.name, applicationClass.enhancedByteCode, 0, applicationClass.enhancedByteCode.length, protectionDomain);
                resolveClass(applicationClass.javaClass);
                if (!applicationClass.isClass()) {
                    applicationClass.javaPackage = applicationClass.javaClass.getPackage();
                }

                if (Logger.isTraceEnabled()) {
                    Logger.trace("%sms to load class %s from cache", System.currentTimeMillis() - start, name);
                }

                return applicationClass.javaClass;
            }
            if (applicationClass.javaByteCode != null || applicationClass.compile() != null) {
                applicationClass.enhance();
                applicationClass.javaClass = defineClass(applicationClass.name, applicationClass.enhancedByteCode, 0, applicationClass.enhancedByteCode.length, protectionDomain);
                BytecodeCache.cacheBytecode(applicationClass.enhancedByteCode, name, applicationClass.javaSource);
                resolveClass(applicationClass.javaClass);
                if (!applicationClass.isClass()) {
                    applicationClass.javaPackage = applicationClass.javaClass.getPackage();
                }

                if (Logger.isTraceEnabled()) {
                    Logger.trace("%sms to load class %s", System.currentTimeMillis() - start, name);
                }

                return applicationClass.javaClass;
            }
            Play.classes.classes.remove(name);
        }
        return null;
    }

    private String getPackageName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > -1 ? name.substring(0, dot) : "";
    }

    private void loadPackage(String className) {
        // find the package class name
        int symbol = className.indexOf("$");
        if (symbol > -1) {
            className = className.substring(0, symbol);
        }
        symbol = className.lastIndexOf(".");
        if (symbol > -1) {
            className = className.substring(0, symbol) + ".package-info";
        } else {
            className = "package-info";
        }
        if (this.findLoadedClass(className) == null) {
            this.loadApplicationClass(className);
        }
    }

    /**
     * Search for the byte code of the given class.
     */
    protected byte[] getClassDefinition(String name) {
        name = name.replace(".", "/") + ".class";
        InputStream is = this.getResourceAsStream(name);
        if (is == null) {
            return null;
        }
        try {
            return IOUtils.toByteArray(is);
        } catch (Exception e) {
            throw new UnexpectedException(e);
        } finally {
            closeQuietly(is);
        }
    }

    /**
     * You know ...
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        for (VirtualFile vf : Play.javaPath) {
            VirtualFile res = vf.child(name);
            if (res != null && res.exists()) {
                return res.inputstream();
            }
        }
        URL url = this.getResource(name);
        if (url != null) {
            try {
                File file = new File(url.toURI());
                String fileName = file.getCanonicalFile().getName();
                if (!name.endsWith(fileName)) {
                    return null;
                }
            } catch (Exception e) {
            }
        }

        return super.getResourceAsStream(name);
    }

    /**
     * You know ...
     */
    @Override
    public URL getResource(String name) {
        for (VirtualFile vf : Play.javaPath) {
            VirtualFile res = vf.child(name);
            if (res != null && res.exists()) {
                try {
                    return res.getRealFile().toURI().toURL();
                } catch (MalformedURLException ex) {
                    throw new UnexpectedException(ex);
                }
            }
        }
        return super.getResource(name);
    }

    /**
     * You know ...
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> urls = new ArrayList<URL>();
        for (VirtualFile vf : Play.javaPath) {
            VirtualFile res = vf.child(name);
            if (res != null && res.exists()) {
                try {
                    urls.add(res.getRealFile().toURI().toURL());
                } catch (MalformedURLException ex) {
                    throw new UnexpectedException(ex);
                }
            }
        }
        Enumeration<URL> parent = super.getResources(name);
        while (parent.hasMoreElements()) {
            URL next = parent.nextElement();
            if (!urls.contains(next)) {
                urls.add(next);
            }
        }
        final Iterator<URL> it = urls.iterator();
        return new Enumeration<URL>() {

            @Override
            public boolean hasMoreElements() {
                return it.hasNext();
            }

            @Override
            public URL nextElement() {
                return it.next();
            }
        };
    }

    /**
     * Detect Java changes
     */
    public void detectChanges() {
        // Now check for file modification
        List<ApplicationClass> modifieds = new ArrayList<ApplicationClass>();
        for (ApplicationClass applicationClass : Play.classes.all()) {
//        	System.out.println("ApplicationClassLoader: check timestamp: " + applicationClass.name);
            VirtualFile javaFile = applicationClass.javaFile;
			Long lastModified = javaFile.lastModified();
			Long timestamp = applicationClass.timestamp;
			if (timestamp < lastModified) {
                applicationClass.reset();
                modifieds.add(applicationClass);
            }
        }
        Set<ApplicationClass> modifiedWithDependencies = new HashSet<ApplicationClass>();
        modifiedWithDependencies.addAll(modifieds);
        if (modifieds.size() > 0) {
            modifiedWithDependencies.addAll(Play.pluginCollection.onClassesChange(modifieds));
        }
        List<ClassDefinition> newDefinitions = new ArrayList<ClassDefinition>();
        boolean dirtySig = false;

        if (modifiedWithDependencies.size() > 0) {
			Logger.info("start compiling %s modified class(es)", modifiedWithDependencies.size());
			long compileTime = 0;
			long enhanceTime = 0;
			for (ApplicationClass applicationClass : modifiedWithDependencies) {
				long compt = System.currentTimeMillis();
				byte[] compile = applicationClass.compile();
				compileTime += (System.currentTimeMillis() - compt);
				if (compile == null) {
					Play.classes.classes.remove(applicationClass.name);
					 currentState = new ApplicationClassloaderState();//show others that we have changed..
				} else {
					int sigChecksum = applicationClass.sigChecksum;
					
					compt = System.currentTimeMillis();
					applicationClass.enhance();
					enhanceTime += (System.currentTimeMillis() - compt);
					
					if (sigChecksum != applicationClass.sigChecksum) {
						dirtySig = true;
					}
					BytecodeCache.cacheBytecode(applicationClass.enhancedByteCode, applicationClass.name, applicationClass.javaSource);
					// bran
					if (applicationClass.javaClass == null) {
						String msg = "javaClass is null: " + applicationClass.name;
						Logger.error(msg);
						throw new RuntimeException(msg);
					} else if (applicationClass.enhancedByteCode == null) {
						String msg = "enhancedByteCode is null: " + applicationClass.name;
						Logger.error(msg);
						throw new RuntimeException(msg);
					} else {
						newDefinitions.add(new ClassDefinition(applicationClass.javaClass, applicationClass.enhancedByteCode));
						currentState = new ApplicationClassloaderState();//show others that we have changed..
					}					
				}
			}
			Logger.info("Finished compiling and enhancing. Took  %s (ms) to compile, %s (ms) to enhance ", compileTime, enhanceTime);
		}
        
        if (newDefinitions.size() > 0) {
            Cache.clear();
            if (HotswapAgent.enabled) {
                try {
                    HotswapAgent.reload(newDefinitions.toArray(new ClassDefinition[newDefinitions.size()]));
                } catch (Throwable e) {
                    throw new RuntimeException("Need reload: hotswap agent failed to reload: " + e + e.getMessage());
                }
            } else {
                throw new RuntimeException("Need reload: HotSwapAgent is not enabled.");
            }
        }
        // Check signature (variable name & annotations aware !)
        if (dirtySig) {
        	// bran: experimental optimization for single file change. It used to throw an exception right away
        	if (newDefinitions.size() >= 1)
        		if (fromMultipleSourceFile(newDefinitions))
        			throw new RuntimeException("Signature change from multiple source file!");
        		else {
        			// optimization for japid
        			ClassDefinition def = newDefinitions.get(0);
        			if (def.getDefinitionClass().getName().startsWith("japidviews")) {
        				Logger.info("japid view signature changes are from the same source file. Try ignoring dirtySig to  avoid whole-sale reloading");
        			}
        			else {
        				throw new RuntimeException("Signature changed from multiple source file!");
        			}
        		}
        }

        // Now check if there is new classes or removed classes
        int hash = computePathHash();
        if (hash != this.pathHash) {
            // Remove class for deleted files !!
            for (ApplicationClass applicationClass : Play.classes.all()) {
                if (!applicationClass.javaFile.exists()) {
                    Play.classes.classes.remove(applicationClass.name);
                    currentState = new ApplicationClassloaderState();//show others that we have changed..
                }
                if (applicationClass.name.contains("$")) {
                    Play.classes.classes.remove(applicationClass.name);
                    currentState = new ApplicationClassloaderState();//show others that we have changed..
                    // Ok we have to remove all classes from the same file ...
                    VirtualFile vf = applicationClass.javaFile;
                    for (ApplicationClass ac : Play.classes.all()) {
                        if (ac.javaFile.equals(vf)) {
                            Play.classes.classes.remove(ac.name);
                        }
                    }
                }
            }
            throw new RuntimeException("Some file on Path has changed(added, removed).");
        }
    }
    private static boolean fromMultipleSourceFile(List<ClassDefinition> newDefinitions) {
    	String cname = null;
    	for (ClassDefinition def: newDefinitions) {
    		String cn = def.getDefinitionClass().getName();
    		int i = cn.indexOf('$');
    		if (i > 0)
    			cn = cn.substring(0, i);
    		if (cname == null)
    			cname = cn;
    		else
    			if (cname.equals(cn)) {
    				// same file
    			}
    			else {
    				return true;
    			}
    	}
    	return false;
	}
	/**
     * Used to track change of the application sources path
     */
    int pathHash = 0;

    int computePathHash() {
        return classStateHashCreator.computePathHash(Play.javaPath);
    }

    /**
     * Try to load all .java files found.
     * @return The list of well defined Class
     */
    public List<Class> getAllClasses() {
        if (allClasses == null) {
            allClasses = new ArrayList<Class>();

            if (Play.usePrecompiled) {

                List<ApplicationClass> applicationClasses = new ArrayList<ApplicationClass>();
                scanPrecompiled(applicationClasses, "", Play.getVirtualFile("precompiled/java"));
                Play.classes.clear();
                for (ApplicationClass applicationClass : applicationClasses) {
                    Play.classes.add(applicationClass);
                    Class clazz = loadApplicationClass(applicationClass.name);
                    applicationClass.javaClass = clazz;
                    applicationClass.compiled = true;
                    allClasses.add(clazz);
                }

            } else {

                long t= System.currentTimeMillis();
                
                if(!Play.pluginCollection.compileSources()) {
                    List<ApplicationClass> all = new ArrayList<ApplicationClass>();

                    for (VirtualFile virtualFile : Play.javaPath) {
                        all.addAll(getAllClasses(virtualFile));
                    }
                    List<String> classNames = new ArrayList<String>();
                    for (int i = 0; i < all.size(); i++) {
                        ApplicationClass applicationClass = all.get(i);
                        if (applicationClass != null && !applicationClass.compiled && applicationClass.isClass()) {
                            classNames.add(all.get(i).name);
                        }
                    }

                    // bran: display compilation time
                    if (classNames.size() > 0) {
		                Logger.debug("start compiling all " + classNames.size() + " class(es). ");
		                
		                t= System.currentTimeMillis();
		                try {
							Play.classes.compiler.compile(classNames.toArray(new String[classNames.size()]));
						} catch (CompilationException e) {
							throw e;
//							throw Play.mapJapidJavCodeError(e);
						}
		                Logger.debug("compiling took(ms): " + (System.currentTimeMillis() - t));
	                }
	                
                    Logger.debug("start loading all " + classNames.size() + " classes. ");
	                t= System.currentTimeMillis();


                }

                for (ApplicationClass applicationClass : Play.classes.all()) {
                    Class clazz = loadApplicationClass(applicationClass.name);
                    if (clazz != null) {
                        allClasses.add(clazz);
                    }
                }
                Logger.debug("loading took(ms): " + (System.currentTimeMillis() - t));

                Collections.sort(allClasses, new Comparator<Class>() {

                    @Override
                    public int compare(Class o1, Class o2) {
                        return o1.getName().compareTo(o2.getName());
                    }
                });
            }
            allClassesByNormalizedName = new HashMap<String, ApplicationClass>(allClasses.size());
            for (ApplicationClass clazz : Play.classes.all()) {
                allClassesByNormalizedName.put(clazz.name.toLowerCase(), clazz);
                if (clazz.name.contains("$")) {
                    allClassesByNormalizedName.put(StringUtils.replace(clazz.name.toLowerCase(), "$", "."), clazz);
                }
            }
        }
        return allClasses;
    }
    
    List<Class> allClasses = null;

	private static Pattern classPattern = Pattern.compile("\\s+class\\s([a-zA-Z0-9_]+)\\s+");


    Map<String, ApplicationClass> allClassesByNormalizedName = null;


    /**
     * Retrieve all application classes assignable to this class.
     * @param clazz The superclass, or the interface.
     * @return A list of class
     */
    public List<Class> getAssignableClasses(Class clazz) {
        if (clazz == null) {
            return Collections.emptyList();
        }
        getAllClasses();
        List<Class> results = assignableClassesByName.get(clazz.getName());
        if (results != null) {
            return results;
        } else {
            results = new ArrayList<Class>();
            for (ApplicationClass c : Play.classes.getAssignableClasses(clazz)) {
                results.add(c.javaClass);
            }
            // cache assignable classes
            assignableClassesByName.put(clazz.getName(), results);
        }
        return results;
    }

    // assignable classes cache
    Map<String, List<Class>> assignableClassesByName = new HashMap<String, List<Class>>(100);

    /**
     * Find a class in a case insensitive way
     * @param name The class name.
     * @return a class
     * bran: TODO: cache the lookup for performance
     */
    public Class getClassIgnoreCase(String name) {
        getAllClasses();
        String nameLowerCased = name.toLowerCase();
        ApplicationClass c = allClassesByNormalizedName.get(nameLowerCased);
        if (c != null) {
            if (Play.usePrecompiled) {
                return c.javaClass;
            }
            return loadApplicationClass(c.name);
        }
        return null;
    }

    /**
     * Retrieve all application classes with a specific annotation.
     * @param clazz The annotation class.
     * @return A list of class
     */
    public List<Class> getAnnotatedClasses(Class<? extends Annotation> clazz) {
        getAllClasses();
        List<Class> results = new ArrayList<Class>();
        for (ApplicationClass c : Play.classes.getAnnotatedClasses(clazz)) {
            results.add(c.javaClass);
        }
        return results;
    }

    public List<Class> getAnnotatedClasses(Class[] clazz) {
        List<Class> results = new ArrayList<Class>();
        for (Class<? extends Annotation> cl : clazz) {
            results.addAll(getAnnotatedClasses(cl));
        }
        return results;
    }

    // ~~~ Intern
    List<ApplicationClass> getAllClasses(String basePackage) {
        List<ApplicationClass> res = new ArrayList<ApplicationClass>();
        for (VirtualFile virtualFile : Play.javaPath) {
            res.addAll(getAllClasses(virtualFile, basePackage));
        }
        return res;
    }

    List<ApplicationClass> getAllClasses(VirtualFile path) {
        return getAllClasses(path, "");
    }

    List<ApplicationClass> getAllClasses(VirtualFile path, String basePackage) {
        if (basePackage.length() > 0 && !basePackage.endsWith(".")) {
            basePackage += ".";
        }
        List<ApplicationClass> res = new ArrayList<ApplicationClass>();
        for (VirtualFile virtualFile : path.list()) {
            scan(res, basePackage, virtualFile);
        }
        return res;
    }

    void scan(List<ApplicationClass> classes, String packageName, VirtualFile current) {
        if (!current.isDirectory()) {
            if (current.getName().endsWith(".java") && !current.getName().startsWith(".")) {
                String classname = packageName + current.getName().substring(0, current.getName().length() - 5);
                classes.add(Play.classes.getApplicationClass(classname));
            }
        } else {
            for (VirtualFile virtualFile : current.list()) {
                scan(classes, packageName + current.getName() + ".", virtualFile);
            }
        }
    }

    void scanPrecompiled(List<ApplicationClass> classes, String packageName, VirtualFile current) {
        if (!current.isDirectory()) {
            if (current.getName().endsWith(".class") && !current.getName().startsWith(".")) {
                String classname = packageName.substring(5) + current.getName().substring(0, current.getName().length() - 6);
                classes.add(new ApplicationClass(classname));
            }
        } else {
            for (VirtualFile virtualFile : current.list()) {
                scanPrecompiled(classes, packageName + current.getName() + ".", virtualFile);
            }
        }
    }

    @Override
    public String toString() {
        return "(play) " + (allClasses == null ? "" : allClasses.toString());
    }

}
