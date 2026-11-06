import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import com.sun.source.util.JavacTask;
import java.util.List;

/** Use javac's parser without linking Android SDK or resolving external imports. */
public class ParseJavaSources {
    public static void main(String[] paths) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> inputs = files.getJavaFileObjectsFromStrings(List.of(paths));
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "-source", "17"), null, inputs);
            int count = 0;
            for (var tree : task.parse()) count++;
            boolean hadError = false;
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    System.err.println(d);
                    hadError = true;
                }
            }
            if (hadError) System.exit(1);
            System.out.println("PASS javac syntax parse: " + count + " Java source units (SDK linking NOT tested)");
        }
    }
}
