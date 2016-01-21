package lombok.javac.handlers;

import static lombok.javac.Javac.*;
import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import lombok.Def;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCConditional;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCReturn;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeApply;
import com.sun.tools.javac.tree.JCTree.JCTypeCast;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

/**
 * Handles the {@code lombok.Def} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleDef extends JavacAnnotationHandler<Def> {

	private static final String VOID = "void";
	private static final String LOMBOK_DEFAULT = "lombok.Def";
	private static final String JAVA_OPTIONAL = "java.util.Optional";
	private static final String MAP_NAME = "paramsMap";

	@Override public void handle(AnnotationValues<Def> annotation, JCAnnotation ast, JavacNode annotationNode) {
		JavacNode paramNode = annotationNode.up();
		switch(paramNode.getKind()) {
			case ARGUMENT:
				java.util.List<JCMethodDecl> methodsToInject = createMethodsForOptionals(paramNode);
				for(JCMethodDecl methodToInject : methodsToInject) {
					// From the perspective of the method parameter, the class is two levels up.
					// First level up is the method, then the class.
					JavacHandlerUtil.injectMethod(paramNode.up().up(), methodToInject);
				}
				return;
			default:
				throw new IllegalArgumentException("@Def is only valid on method arguments.");
		}
	}
	
	private java.util.List<JCMethodDecl> createMethodsForOptionals(JavacNode paramNode) {
                java.util.List<JCMethodDecl> methodsToInject = new java.util.ArrayList<JCMethodDecl>();
		JCMethodDecl methodDecl = getJcMethodDeclFromNode(paramNode.up());
		JCClassDecl classDecl = (JCClassDecl) paramNode.up().up().get();

		if(paramNode.getKind() != Kind.ARGUMENT) {
                        throw new IllegalArgumentException("Expected annotation to be applied to parameter.");
                }
		if(!nodeIsTheFirstOptAnnotationInParams(paramNode)) {
			return new java.util.ArrayList<JCMethodDecl>();
		}
		if(nonOptionalComesAfterOptional(methodDecl)) {
			throw new IllegalArgumentException("Non-optional params cannot come after @Def params.");
		}

		JavacTreeMaker treeMaker = paramNode.getTreeMaker();
		JCExpression emptyOptional = getEmptyOptionalJcExpression(treeMaker, paramNode);

		JCModifiers modifiers = methodDecl.getModifiers();
		List<JCTypeParameter> methodGenericTypes = methodDecl.getTypeParameters();
		JCExpression methodReturnType = (JCExpression) methodDecl.getReturnType();
		Name methodName = methodDecl.getName();
		List<JCVariableDecl> methodParams = methodDecl.getParameters();
		List<JCExpression> methodThrows = methodDecl.getThrows();
	
		List<JCVariableDecl> requiredMethodParams = List.<JCVariableDecl>nil();
		java.util.List<JCVariableDecl> optionalMethodParams = new java.util.ArrayList<JCVariableDecl>();
		java.util.List<String> defaultValues = new java.util.ArrayList<String>();
		for(JCVariableDecl var : methodParams) {
			boolean optional = false;
			for(JCAnnotation ann : var.getModifiers().getAnnotations()) {
				if(ann.type.toString().equals(LOMBOK_DEFAULT)) {
					optionalMethodParams.add(var);
					if(isJavaOptional(var)) {
						defaultValues.add(""); // No default string needed for optionals, default is always Optional.empty().
					} else {
						addAnnotationDefault(ann, defaultValues);
					}
					optional = true;
				}
			}
			if(!optional) requiredMethodParams = requiredMethodParams.append(var);
		}

		// Create a version of the method with all optional parameters passed in as a Map<String, Object>.
		List<JCExpression> innerMethodParamsForMapMethod = List.<JCExpression>nil();
		for(JCVariableDecl var : requiredMethodParams) {
			innerMethodParamsForMapMethod = innerMethodParamsForMapMethod.append(treeMaker.Ident(var.name));
		}
		// For each of the optional params, we will make a JCConditional (JCExpression) of the form
		// map.containsKey(argName) ? (TypeCast) map.get(argName) : defaultValue
		// and add that JCConditional to the inner method params.
		for(int i=0; i<optionalMethodParams.size(); i++) {
			String argName = optionalMethodParams.get(i).getName().toString();
			JCExpression argNameAsExpression = treeMaker.Literal(getObject("java.lang.String", argName));
			List<JCExpression> argNameExpressions = List.<JCExpression>nil();
			argNameExpressions = argNameExpressions.append(argNameAsExpression);

			// First make the conditional expression, map.containsKey(argName)
			JCExpression containsKey = JavacHandlerUtil.chainDots(paramNode, MAP_NAME, "containsKey");
			JCMethodInvocation containsKeyInvocation = treeMaker.Apply(List.<JCExpression>nil(), containsKey, argNameExpressions);

			// Then make map.get(argName)
			JCExpression get = JavacHandlerUtil.chainDots(paramNode, MAP_NAME, "get");
			JCMethodInvocation getInvocation = treeMaker.Apply(List.<JCExpression>nil(), get, argNameExpressions);
			JCTypeCast typeCast = treeMaker.TypeCast(optionalMethodParams.get(i).vartype, getInvocation);

			// Last get the default value expression
			JCExpression defaultValue;
			String value = defaultValues.get(i);
			String type = optionalMethodParams.get(i).getType().type.tsym.toString();
			if(type.equals(JAVA_OPTIONAL)) {
				defaultValue = emptyOptional;
			} else {
				defaultValue = treeMaker.Literal(getObject(type, value));
			}

			JCConditional conditional = treeMaker.Conditional(containsKeyInvocation, typeCast, defaultValue);
			// Add the JCConditional to the methodParams
			innerMethodParamsForMapMethod = innerMethodParamsForMapMethod.append(conditional);						
		}
		JCExpression innerMethod = JavacHandlerUtil.chainDotsString(paramNode, methodName.toString());
		List<JCExpression> typeParamExpressions = getTypeParamsAsJcExpressions(methodGenericTypes);
		JCMethodInvocation methodInvocation = treeMaker.Apply(typeParamExpressions, innerMethod, innerMethodParamsForMapMethod);
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
		if(methodReturnType.toString().equals(VOID)) {
			JCStatement statement = treeMaker.Exec((JCExpression) methodInvocation.getTree());
			statements.add(statement);
		} else {
			JCReturn returnStatement = treeMaker.Return((JCExpression) methodInvocation.getTree());
			statements.add(returnStatement);
		}
		JCBlock methodBody = treeMaker.Block(0, statements.toList());

		// Create the outer method params, e.g. the non-optional method params followed by a Map<String, Object> for the optionals.
		char[] javaArr = "java".toCharArray();
		Name javaName = classDecl.name.table.fromChars(javaArr, 0, javaArr.length);
		JCIdent javaIdent = treeMaker.Ident(javaName);

		char[] javaUtilArr = "util".toCharArray();
		Name javaUtilName = classDecl.name.table.fromChars(javaUtilArr, 0, javaUtilArr.length);
		JCFieldAccess javaUtilField = treeMaker.Select(javaIdent, javaUtilName);

		char[] javaUtilMapArr = "Map".toCharArray();
		Name javaUtilMapName = classDecl.name.table.fromChars(javaUtilMapArr, 0, javaUtilMapArr.length);
		JCFieldAccess javaUtilMapField = treeMaker.Select(javaUtilField, javaUtilMapName);

		char[] stringCharArr = "String".toCharArray();
		char[] objectCharArr = "Object".toCharArray();
		
		Name stringName = classDecl.name.table.fromChars(stringCharArr, 0, stringCharArr.length);
		Name objectName = classDecl.name.table.fromChars(objectCharArr, 0, objectCharArr.length);
		JCIdent stringIdent = treeMaker.Ident(stringName);
		JCIdent objectIdent = treeMaker.Ident(objectName);
		List<JCExpression> argsList = List.<JCExpression>nil();
		argsList = argsList.append(stringIdent);
		argsList = argsList.append(objectIdent);

		char[] nameOfMapArr = MAP_NAME.toCharArray();
		Name varName = classDecl.name.table.fromChars(nameOfMapArr, 0, nameOfMapArr.length);

		JCTypeApply typeApply = treeMaker.TypeApply(javaUtilMapField, argsList);
		JCVariableDecl mapVar = treeMaker.VarDef(treeMaker.Modifiers(Flags.PARAMETER | Flags.FINAL), varName, typeApply, null);
		mapVar.pos = 50000;
		
		JCExpression defaultValue = null;
		JCBlock methodBodyWithAllDefaults = getMethodBodyWithAllDefaults(paramNode, treeMaker, requiredMethodParams, 
								optionalMethodParams, defaultValues, methodReturnType, methodName, methodGenericTypes);
		JCMethodDecl methodWithAllDefaults = treeMaker.MethodDef(modifiers, methodName, methodReturnType, methodGenericTypes,
								requiredMethodParams, methodThrows, methodBodyWithAllDefaults, defaultValue);

		requiredMethodParams = requiredMethodParams.append(mapVar);
		JCMethodDecl methodWithMap = treeMaker.MethodDef(modifiers, methodName, methodReturnType, methodGenericTypes,
							 requiredMethodParams, methodThrows, methodBody, defaultValue);

		methodsToInject.add(methodWithAllDefaults);
		methodsToInject.add(methodWithMap);
		return methodsToInject;
	}

	private static JCBlock getMethodBodyWithAllDefaults(JavacNode paramNode, JavacTreeMaker treeMaker, List<JCVariableDecl> requiredMethodParams, 
							java.util.List<JCVariableDecl> optionalMethodParams, java.util.List<String> defaultValues,
							JCExpression methodReturnType, Name methodName, List<JCTypeParameter> methodGenericTypes) {
		JCExpression emptyOptional = getEmptyOptionalJcExpression(treeMaker, paramNode);
		List<JCExpression> innerMethodParamExpressions = List.<JCExpression>nil();
		// First, add all non-optional params to the inner method.
		for(JCVariableDecl var : requiredMethodParams) {
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

		return methodBody;
	}

	private static boolean nonOptionalComesAfterOptional(JCMethodDecl methodDecl) {
		List<JCVariableDecl> methodParams = methodDecl.getParameters();
		boolean optionalFound = false;
                for(JCVariableDecl var : methodParams) {
                        boolean thisVarIsOptional = false;
                        for(JCAnnotation ann : var.getModifiers().getAnnotations()) {
                                if(ann.type.toString().equals(LOMBOK_DEFAULT)) {
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
			String value = assign.rhs.toString();
			value = value.replaceAll("\"", "");
			defaultValues.add(value);
			foundDefault = true;
			break;
		}
		if(!foundDefault) {
			throw new IllegalArgumentException("You must provide a default value when using @Def with a non-java.util.Optional method parameter.");
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
			throw new IllegalArgumentException("chars are currently not supported by @Def.");
		} else if(type.equals("java.lang.String")) {
			return value;
		} else throw new IllegalArgumentException("@Def can only be used on non-char primitives, Strings, and Optionals.");
	}

	private static boolean nodeIsTheFirstOptAnnotationInParams(JavacNode paramNode) {
		JCMethodDecl methodDecl = getJcMethodDeclFromNode(paramNode.up());
		List<JCVariableDecl> methodParams = methodDecl.getParameters();
		for(JCVariableDecl var : methodParams) {
			for(JCAnnotation ann : var.getModifiers().getAnnotations()) {
				if(ann.type.toString().equals(LOMBOK_DEFAULT)) {
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
