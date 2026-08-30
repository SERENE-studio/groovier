package com.bluesky.groovier.sandbox.transformer;

import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * 编译定制器:在 CANONICALIZATION 阶段对每个类应用 GroovierTransformer(沙箱 AST 拦截)。
 */
public class GroovierCompiler extends CompilationCustomizer {

    public GroovierCompiler() {
        super(CompilePhase.CANONICALIZATION);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        if (classNode == null) return;
        new GroovierTransformer(source).visitClass(classNode);
    }
}
