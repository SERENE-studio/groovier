package com.bluesky.groovier.sandbox.transformer;

import com.bluesky.groovier.sandbox.security.GroovySecurityManager;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 * 编译期沙箱拦截:遍历 AST,拦截黑名单类/包引用、黑名单方法调用(如 System.exit、String.execute)。
 * 命中即产生编译错误,脚本不执行,服务器不受影响。
 */
public class GroovierTransformer extends ClassCodeExpressionTransformer {

    private final SourceUnit sourceUnit;

    public GroovierTransformer(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    @Override
    public Expression transform(Expression exp) {
        if (exp instanceof MethodCallExpression mce) {
            checkMethodCall(mce);
        } else if (exp instanceof ClassExpression ce) {
            checkType(ce.getType(), ce);
        } else if (exp instanceof ConstructorCallExpression cce) {
            checkType(cce.getType(), cce);
        }
        return super.transform(exp);
    }

    private void checkMethodCall(MethodCallExpression mce) {
        Expression objectExpr = mce.getObjectExpression();
        String methodName = mce.getMethodAsString();
        if (methodName == null) {
            // M10:GString 动态方法名无法静态判定,保守拒绝编译,防借动态名绕过方法黑名单
            error("Dynamic method names are not allowed in the Groovier sandbox!", mce);
            return;
        }

        if (objectExpr instanceof ClassExpression ce) {
            // 静态调用:ClassName.method(...)
            checkType(ce.getType(), mce);
            String className = ce.getType().getName();
            if (GroovySecurityManager.INSTANCE.isBannedMethod(className, methodName)) {
                error("Method " + className + "." + methodName + " is blacklisted in the Groovier sandbox!", mce);
            }
        } else if (!mce.isImplicitThis() && objectExpr.getType() != null) {
            // 实例调用:按 receiver 类型检查(变量类型在 CANONICALIZATION 已解析)
            // G4(b):OBJECT_TYPE receiver 不再直接跳过,照常走类检查
            ClassNode receiverType = objectExpr.getType();
            String name = receiverType.getName();
            if (GroovySecurityManager.INSTANCE.isBannedClass(name)) {
                error("Class " + name + " is blacklisted in the Groovier sandbox!", mce);
            } else {
                // G4(a):比对 bannedMethods 全表,沿 receiver 继承链上溯(防子类 receiver 绕过声明类判定)
                for (ClassNode t = receiverType; t != null; t = t.getSuperClass()) {
                    if (GroovySecurityManager.INSTANCE.isBannedMethod(t.getName(), methodName)) {
                        error("Method " + t.getName() + "." + methodName + " is blacklisted in the Groovier sandbox!", mce);
                        break;
                    }
                }
            }
        }
    }

    private void checkType(ClassNode type, ASTNode node) {
        if (type == null) return;
        if (!GroovySecurityManager.INSTANCE.isValid(type)) {
            error("Class " + type.getName() + " is blacklisted in the Groovier sandbox!", node);
        }
    }

    private void error(String message, ASTNode node) {
        sourceUnit.addError(new SyntaxException(message, node));
    }
}
