package lombok.javac.handlers;

import static lombok.javac.Javac.*;
import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.Collection;

import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.Opt;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.handlers.JavacHandlerUtil.FieldAccess;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

/**
 * Handles the {@code lombok.Opt} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleOpt extends JavacAnnotationHandler<Opt> {

	private static final String VOID = "void";
	private static final String LOMBOK_OPT = "lombok.Opt";
	private static final String JAVA_OPTIONAL = "java.util.Optional";
	private static final String ANNOTATION_PARAM = "def"; // The parameter to specify the default value using @code lombok.Opt, e.g. @Opt(def="3")

	@Override public void handle(AnnotationValues<Opt> annotation, JCAnnotation ast, JavacNode annotationNode) {
		JavacNode typeNode = annotationNode.up(); // Either a method or a method argument.
		switch(typeNode.getKind()) {
			case METHOD:
				JCMethodDecl methodToInject = createMethodWithoutOptionalsFromMethodNode(typeNode);
				// From the perspective of the method the class is one level up.
				JavacHandlerUtil.injectMethod(typeNode.up(), methodToInject);
				return;
			case ARGUMENT:
				java.util.List<JCMethodDecl> methodsToInject = createMethodsFromParameterNode(typeNode);
				for(JCMethodDecl method : methodsToInject) {
					// From the perspective of the method parameter, the class is two levels up.
					// First level up is the method, then the class.
					JavacHandlerUtil.injectMethod(typeNode.up().up(), method);
				}
				return;
			default:
				throw new IllegalArgumentException("@Opt is only valid on methods and method arguments.");
		}
	}


	private JCMethodDecl createMethodWithoutOptionalsFromMethodNode(JavacNode methodNode) {
		if(methodNode.getKind() != Kind.METHOD) {
			throw new IllegalArgumentException("Expected annotation to be applied to method.");
		}

		JCMethodDecl methodDecl = getJcMethodDeclFromNode(methodNode);
		JavacTreeMaker treeMaker = methodNode.getTreeMaker();
		JCExpression emptyOptional = getEmptyOptionalJcExpression(treeMaker, methodNode);
		
		JCModifiers modifiers = methodDecl.getModifiers();
		List<JCTypeParameter> methodGenericTypes = methodDecl.getTypeParameters();
		JCExpression methodReturnType = (JCExpression) methodDecl.getReturnType();
		Name methodName = methodDecl.getName();
		List<JCVariableDecl> methodParams = methodDecl.getParameters();
		List<JCExpression> methodThrows = methodDecl.getThrows();

		List<JCVariableDecl> nonOptionalMethodParameters = List.<JCVariableDecl>nil();
		List<JCExpression> allMethodParamsAsJcExpressions = List.<JCExpression>nil();
		for(JCVariableDecl var : methodParams) {
			if(var.getType().type.tsym.toString().equals(JAVA_OPTIONAL)) {
				allMethodParamsAsJcExpressions = allMethodParamsAsJcExpressions.append(emptyOptional);	
			} else {
				allMethodParamsAsJcExpressions = allMethodParamsAsJcExpressions.append(treeMaker.Ident(var.name));
				nonOptionalMethodParameters = nonOptionalMethodParameters.append(var);
			}
		}

		if(allMethodParamsAsJcExpressions.size() == nonOptionalMethodParameters.size()) {
			throw new IllegalArgumentException("You have applied @Opt on a method with no java.util.Optional parameters.");
		}
		
		JCExpression innerMethod = JavacHandlerUtil.chainDotsString(methodNode, methodName.toString());
		List<JCExpression> typeParamExpressions = getTypeParamsAsJcExpressions(methodGenericTypes);
		JCMethodInvocation methodInvocation = treeMaker.Apply(typeParamExpressions, innerMethod, allMethodParamsAsJcExpressions);
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
		if(methodReturnType.toString().equals(VOID)) {
			JCStatement statement = treeMaker.Exec((JCExpression) methodInvocation.getTree());
			statements.add(statement);
		} else {
			JCReturn returnStatement = treeMaker.Return((JCExpression) methodInvocation.getTree());
			statements.add(returnStatement);
		}
		JCBlock methodBody = treeMaker.Block(0, statements.toList());

		JCExpression defaultValue = null;
		return treeMaker.MethodDef(modifiers, methodName, methodReturnType, methodGenericTypes, nonOptionalMethodParameters, 
						methodThrows, methodBody, defaultValue);
	}

	private java.util.List<JCMethodDecl> createMethodsFromParameterNode(JavacNode paramNode) {
		java.util.List<JCMethodDecl> methodsToInject = new java.util.ArrayList<JCMethodDecl>();
                JCMethodDecl methodDecl = getJcMethodDeclFromNode(paramNode.up());

		if(paramNode.getKind() != Kind.ARGUMENT) {
                        throw new IllegalArgumentException("Expected annotation to be applied to parameter.");
                }
		if(!nodeIsTheFirstOptAnnotationInParams(paramNode)) {
			return new java.util.ArrayList<JCMethodDecl>();
		}
		if(nonOptionalComesAfterOptional(methodDecl)) {
			throw new IllegalArgumentException("Non-optional params cannot come after @Opt params.");
		}

                JavacTreeMaker treeMaker = paramNode.getTreeMaker();
                JCExpression emptyOptional = getEmptyOptionalJcExpression(treeMaker, paramNode);

                JCModifiers modifiers = methodDecl.getModifiers();
                List<JCTypeParameter> methodGenericTypes = methodDecl.getTypeParameters();
                JCExpression methodReturnType = (JCExpression) methodDecl.getReturnType();
                Name methodName = methodDecl.getName();
                List<JCVariableDecl> methodParams = methodDecl.getParameters();
                List<JCExpression> methodThrows = methodDecl.getThrows();
	
		List<JCVariableDecl> nonOptionalMethodParameters = List.<JCVariableDecl>nil();
		java.util.List<JCVariableDecl> optionalMethodParams = new java.util.ArrayList<JCVariableDecl>();
		java.util.List<String> defaultValues = new java.util.ArrayList<String>();
		for(JCVariableDecl var : methodParams) {
			boolean optional = false;
			for(JCAnnotation ann : var.getModifiers().getAnnotations()) {
				if(ann.type.toString().equals(LOMBOK_OPT)) {
					optionalMethodParams.add(var);
					if(isJavaOptional(var)) {
						defaultValues.add(""); // No default string needed for optionals, default is always Optional.empty()
					} else {
						addAnnotationDefault(ann, defaultValues);
					}
					optional = true;
				}
			}
			if(!optional) nonOptionalMethodParameters = nonOptionalMethodParameters.append(var);
		}

		while(optionalMethodParams.size() > 0) {
			List<JCExpression> innerMethodParamExpressions = List.<JCExpression>nil();

			// First, add all non-optional params to the inner method.			
			for(JCVariableDecl var : nonOptionalMethodParameters) {
				innerMethodParamExpressions = innerMethodParamExpressions.append(treeMaker.Ident(var.name));
			}
			// Then, append all the default values as literals
			for(int i=0; i<defaultValues.size(); i++) {
				String value = defaultValues.get(i);
				String type = optionalMethodParams.get(i).getType().type.tsym.toString();
				if(type.equals(JAVA_OPTIONAL)) {
					innerMethodParamExpressions = innerMethodParamExpressions.append(emptyOptional);
				} else {
					innerMethodParamExpressions = innerMethodParamExpressions.append(treeMaker.Literal(getObject(type, value)));
				}
			}

			JCExpression innerMethod = JavacHandlerUtil.chainDotsString(paramNode, methodName.toString());
			List<JCExpression> typeParamExpressions = getTypeParamsAsJcExpressions(methodGenericTypes);
			JCMethodInvocation methodInvocation = treeMaker.Apply(typeParamExpressions, innerMethod, innerMethodParamExpressions);
			ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
			if(methodReturnType.toString().equals(VOID)) {
				JCStatement statement = treeMaker.Exec((JCExpression) methodInvocation.getTree());
				statements.add(statement);
			} else {
				JCReturn returnStatement = treeMaker.Return((JCExpression) methodInvocation.getTree());
				statements.add(returnStatement);
			}
			JCBlock methodBody = treeMaker.Block(0, statements.toList());
		
			JCExpression defaultValue = null;
			methodsToInject.add(treeMaker.MethodDef(modifiers, methodName, methodReturnType, methodGenericTypes, 
						nonOptionalMethodParameters, methodThrows, methodBody, defaultValue));

			// Now that we added this default method, make the next method with the var as non-default.
			nonOptionalMethodParameters = nonOptionalMethodParameters.append(optionalMethodParams.get(0));
			defaultValues.remove(0);
			optionalMethodParams.remove(0);
		}

		return methodsToInject;
	}

	private static boolean nonOptionalComesAfterOptional(JCMethodDecl methodDecl) {
		List<JCVariableDecl> methodParams = methodDecl.getParameters();
		boolean optionalFound = false;
                for(JCVariableDecl var : methodParams) {
                        boolean thisVarIsOptional = false;
                        for(JCAnnotation ann : var.getModifiers().getAnnotations()) {
                                if(ann.type.toString().equals(LOMBOK_OPT)) {
					optionalFound = true;
					thisVarIsOptional = true;
                                }
                        }
			if(optionalFound && !thisVarIsOptional) return true;
                }
		return false;
	}

	private static void addAnnotationDefault(JCAnnotation ann, java.util.List<String> defaultValues) {
		boolean foundDefault = false;
		for(JCExpression jce : ann.args) {
			JCAssign assign = (JCAssign) jce;
			if(assign.lhs.toString().equals(ANNOTATION_PARAM)) {
				String value = assign.rhs.toString();
				value = value.replaceAll("\"", "");
				defaultValues.add(value);
				foundDefault = true;
				break; // Don't process multiple defaults.
			}
		}
		if(!foundDefault) {
			throw new IllegalArgumentException("You must provide a default value when using @Opt with a non-java.util.Optional method parameter.");
		}
	}

	private static JCMethodDecl getJcMethodDeclFromNode(JavacNode methodNode) {
		return (JCMethodDecl) methodNode.get();
	}

	private static JCExpression getEmptyOptionalJcExpression(JavacTreeMaker treeMaker, JavacNode node) {
		JCExpression optExpression = JavacHandlerUtil.chainDots(node, "Optional", "empty");
		JCMethodInvocation optInvocation = treeMaker.Apply(List.<JCExpression>nil(), optExpression, List.<JCExpression>nil());
		JCExpression optEmpty = (JCExpression) optInvocation.getTree();
		return optEmpty;
	}

	private static boolean isJavaOptional(JCVariableDecl var) {
		String varType = var.getType().type.tsym.toString();
		return varType.equals(JAVA_OPTIONAL);
	}

	private static Object getObject(String type, String value) {
		if(type.equals("int")) {
			return Integer.parseInt(value);
		} else if(type.equals("double")) {
			return Double.parseDouble(value);
		} else if(type.equals("float")) {
			return Float.parseFloat(value);
		} else if(type.equals("short")) {
			return Short.parseShort(value);
		} else if(type.equals("long")) {
			return Long.parseLong(value);
		} else if(type.equals("byte")) {
			return Byte.parseByte(value);
		} else if(type.equals("boolean")) {
			return Boolean.parseBoolean(value);
		} else if(type.equals("char")) {
			throw new IllegalArgumentException("chars are currently not supported by @Opt.");
		} else return value;
	}

	private static boolean nodeIsTheFirstOptAnnotationInParams(JavacNode paramNode) {
		JCMethodDecl methodDecl = getJcMethodDeclFromNode(paramNode.up());
		List<JCVariableDecl> methodParams = methodDecl.getParameters();
		for(JCVariableDecl var : methodParams) {
			for(JCAnnotation ann : var.getModifiers().getAnnotations()) {
				if(ann.type.toString().equals(LOMBOK_OPT)) {
					if(var.getName().toString().equals(paramNode.getName().toString())) {
						return true;
					}
					else {
						return false;
					}
				}
			}
		}
		return false;
	}

	private static List<JCExpression> getTypeParamsAsJcExpressions(List<JCTypeParameter> typeParams) {
		List<JCExpression> typeParamExpressions = List.<JCExpression>nil();
		for(JCTypeParameter param : typeParams) {
			typeParamExpressions = typeParamExpressions.append((JCExpression) param.getTree());
		}
		return typeParamExpressions;
	}

}
