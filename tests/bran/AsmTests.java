/**
 * 
 */
package bran;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;

import play.Invoker.Suspend;
import play.classloading.enhancers.ControllersEnhancer.ControllerInstrumentation;
import play.mvc.Controller;
import play.mvc.results.Redirect;
import play.mvc.results.Result;

/**
 * @author bran
 *
 */
public class AsmTests {

	@Test
	public void testAsmifier() throws IOException {
		ClassReader cr = new ClassReader(AsmTests.class.getName());
		cr.accept(new TraceClassVisitor(null, new ASMifier(), new PrintWriter(System.out)), ClassReader.SKIP_DEBUG);
	}

	// bran: used to be inserted to the beginning of an action call
	public static void beforeMethod(Method m, Object... args) throws Redirect {
		if (!ControllerInstrumentation.isActionCallAllowed()) {
			Controller.redirect(m.getDeclaringClass().getName() + "." + m.getName(), args);
		} else {
			ControllerInstrumentation.stopActionCall();
		}
	}

	public static void so(String a, long b, boolean c, double d, String ee) {
		
		try {
//			beforeMethod(null, "sss", b, c, d, ee, a, b);
			int s = 11;
		}catch (RuntimeException e) {
			int ss = 100;
		}catch (Throwable e) {
			if (e instanceof Result || e instanceof Suspend)
				throw e;
		}
	}
	
}
