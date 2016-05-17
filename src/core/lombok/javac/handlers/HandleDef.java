package lombok.javac.handlers;

import static lombok.javac.Javac.*;
import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.LinkedHashMap;
import java.util.Map;

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
 *
 * May not work correctly for java versions prior to 8 without some tweaks.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleDef extends JavacAnnotationHandler<Def> {

	private static final String VOID = "void";
	private static final String LOMBOK_DEF = "lombok.Def";
	private static final String JAVA_UTIL_OPTIONAL = "java.util.Optional";
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
		if(!nodeIsTheFirstDefAnnotationInParams(paramNode)) {
			return new java.util.ArrayList<JCMethodDecl>();
		}
		if(nonOptionalComesAfterOptional(methodDecl)) {
			throw new IllegalArgumentException("Non-optional params cannot come after @Def params.");
		}

		JavacTreeMaker treeMaker = paramNode.getTreeMaker();
		List<JCVariableDecl> methodParams = methodDecl.getParameters();
		List<JCVariableDecl> requiredMethodParams = List.<JCVariableDecl>nil();
		Map<JCVariableDecl, String> optionalArgumentToDefaultValueMap = new LinkedHashMap<JCVariableDecl, String>();
		for(JCVariableDecl var : methodParams) {
			boolean optional = false;
			for(JCAnnotation ann : var.getModifiers().getAnnotations()) {
				if(ann.type.toString().equals(LOMBOK_DEF)) {
					if(isJavaOptional(var)) {
						// No default string needed for optionals, default is always Optional.empty().
						optionalArgumentToDefaultValueMap.put(var, "");
					} else {
						optionalArgumentToDefaultValueMap.put(var, getDefaultFromAnnotation(ann));
					}
					optional = true;
				}
			}
			if(!optional) requiredMethodParams = requiredMethodParams.append(var);
		}

		JCMethodDecl methodWithoutDefaults = getMethodWithoutDefaults(methodDecl, paramNode, requiredMethodParams,
		                                                              optionalArgumentToDefaultValueMap, treeMaker);
		JCMethodDecl methodWithMap = getMethodWithMap(methodDecl, paramNode, requiredMethodParams,
		                                              optionalArgumentToDefaultValueMap, treeMaker);
		methodsToInject.add(methodWithoutDefaults);
		methodsToInject.add(methodWithMap);
		return methodsToInject;
	}

	/*
	 * Creates a version of the method without the default parameters in the signature.
	 * e.g. foo(String x, String y, @Def("2") int z) {}
	 * will have foo(String x, String y) { foo(x, y, 2); } auto-generated.
	 */
	private JCMethodDecl getMethodWithoutDefaults(JCMethodDecl originalMethod,
	                                              JavacNode paramNode,
	                                              List<JCVariableDecl> requiredMethodParams,
	                                              Map<JCVariableDecl, String> optionalArgumentToDefaultValueMap,
	                                              JavacTreeMaker treeMaker) {
		JCExpression emptyOptional = getEmptyOptionalJcExpression(treeMaker, paramNode);
		JCExpression methodReturnType = (JCExpression) originalMethod.getReturnType();

		List<JCExpression> innerMethodParamExpressions = List.<JCExpression>nil();
		// First, add all non-optional params to the inner method.
		for(JCVariableDecl var : requiredMethodParams) {
			innerMethodParamExpressions = innerMethodParamExpressions.append(treeMaker.Ident(var.name));
		}
		// Then, append all the default values as literals
		for(Map.Entry<JCVariableDecl, String> entry : optionalArgumentToDefaultValueMap.entrySet()) {
			String type = entry.getKey().getType().type.tsym.toString();
			String value = entry.getValue();
			if(type.equals(JAVA_UTIL_OPTIONAL)) {
				innerMethodParamExpressions = innerMethodParamExpressions.append(emptyOptional);
			} else if(type.equals("char")) {
				// Compiler complains if a char is passed to treeMaker.literal
				JCExpression intLiteral = treeMaker.Literal(getObject(type, value));
				JCTypeCast castIntToChar = treeMaker.TypeCast(entry.getKey().getType(), intLiteral);
				innerMethodParamExpressions = innerMethodParamExpressions.append(castIntToChar);
			} else {
				innerMethodParamExpressions = innerMethodParamExpressions.append(treeMaker.Literal(getObject(type, value)));
			}
		}

		JCExpression innerMethod = JavacHandlerUtil.chainDotsString(paramNode, originalMethod.getName().toString());
		List<JCExpression> typeParamExpressions = getTypeParamsAsJcExpressions(originalMethod.getTypeParameters());
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

		JCModifiers modifiers = originalMethod.getModifiers();
		List<JCTypeParameter> methodGenericTypes = originalMethod.getTypeParameters();
		Name methodName = originalMethod.getName();
		List<JCVariableDecl> methodParams = originalMethod.getParameters();
		List<JCExpression> methodThrows = originalMethod.getThrows();
		return treeMaker.MethodDef(modifiers, methodName, methodReturnType, methodGenericTypes,
		                           requiredMethodParams, methodThrows, methodBody, null);
	}

	/*
	 * Creates a version of the method with the default params passed in as a Map<String, Object>.
	 * e.g. foo(String x, @Def("2") int y) {}
	 * will auto-generate
	 * foo(String x, Map<String, Object> paramsMap) {
	 *     foo(x, paramsMap.containsKey("y") ? (int) paramsMap.get("y") : 2);
	 * }
	 */
	private JCMethodDecl getMethodWithMap(JCMethodDecl originalMethod,
	                                      JavacNode paramNode,
	                                      List<JCVariableDecl> requiredMethodParams,
	                                      Map<JCVariableDecl, String> optionalArgumentToDefaultValueMap,
	                                      JavacTreeMaker treeMaker) {
		JCClassDecl classDecl = (JCClassDecl) paramNode.up().up().get();
		JCExpression emptyOptional = getEmptyOptionalJcExpression(treeMaker, paramNode);
		JCExpression methodReturnType = (JCExpression) originalMethod.getReturnType();

		// Create a version of the method with all optional parameters passed in as a Map<String, Object>.
		List<JCExpression> innerMethodParamsForMapMethod = List.<JCExpression>nil();
		for(JCVariableDecl var : requiredMethodParams) {
			innerMethodParamsForMapMethod = innerMethodParamsForMapMethod.append(treeMaker.Ident(var.name));
		}
		// For each of the optional params, we will make a JCConditional (JCExpression) of the form
		// map.containsKey(argName) ? (TypeCast) map.get(argName) : defaultValue
		// and add that JCConditional to the inner method params.
		for(Map.Entry<JCVariableDecl, String> entry : optionalArgumentToDefaultValueMap.entrySet()) {
			JCVariableDecl variable = entry.getKey();
			String value = entry.getValue();
			String argName = variable.getName().toString();
			JCExpression argNameAsExpression = treeMaker.Literal(getObject("java.lang.String", argName));
			List<JCExpression> argNameExpressions = List.<JCExpression>nil();
			argNameExpressions = argNameExpressions.append(argNameAsExpression);

			// First make the conditional expression, map.containsKey(argName)
			JCExpression containsKey = JavacHandlerUtil.chainDots(paramNode, MAP_NAME, "containsKey");
			JCMethodInvocation containsKeyInvocation = treeMaker.Apply(List.<JCExpression>nil(), containsKey, argNameExpressions);

			// Then make (TypeCast) map.get(argName)
			JCExpression get = JavacHandlerUtil.chainDots(paramNode, MAP_NAME, "get");
			JCMethodInvocation getInvocation = treeMaker.Apply(List.<JCExpression>nil(), get, argNameExpressions);
			JCTypeCast typeCast = treeMaker.TypeCast(variable.vartype, getInvocation);

			// Finally get the default value expression.
			JCExpression defaultValue;
			String type = variable.getType().type.tsym.toString();
			if(type.equals(JAVA_UTIL_OPTIONAL)) {
				defaultValue = emptyOptional;
			} else if(type.equals("char")) {
				// Compiler complains if a char is passed to treeMaker.literal
				JCExpression intLiteral = treeMaker.Literal(getObject(type, value));
				JCTypeCast castIntToChar = treeMaker.TypeCast(variable.getType(), intLiteral);
				defaultValue = castIntToChar;
			} else {
				defaultValue = treeMaker.Literal(getObject(type, value));
			}

			JCConditional conditional = treeMaker.Conditional(containsKeyInvocation, typeCast, defaultValue);
			// Add the JCConditional to the methodParams.
			innerMethodParamsForMapMethod = innerMethodParamsForMapMethod.append(conditional);
		}
		JCExpression innerMethod = JavacHandlerUtil.chainDotsString(paramNode, originalMethod.getName().toString());
		List<JCExpression> typeParamExpressions = getTypeParamsAsJcExpressions(originalMethod.getTypeParameters());
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
		requiredMethodParams = requiredMethodParams.append(mapVar);

		JCModifiers modifiers = originalMethod.getModifiers();
		List<JCTypeParameter> methodGenericTypes = originalMethod.getTypeParameters();
		Name methodName = originalMethod.getName();
		List<JCVariableDecl> methodParams = originalMethod.getParameters();
		List<JCExpression> methodThrows = originalMethod.getThrows();
		return treeMaker.MethodDef(modifiers, methodName, methodReturnType, methodGenericTypes,
		                           requiredMethodParams, methodThrows, methodBody, null);
	}

	private static boolean nonOptionalComesAfterOptional(JCMethodDecl methodDecl) {
		List<JCVariableDecl> methodParams = methodDecl.getParameters();
		boolean optionalFound = false;
		for(JCVariableDecl var : methodParams) {
			boolean thisVarIsOptional = false;
			for(JCAnnotation ann : var.getModifiers().getAnnotations()) {
				if(ann.type.toString().equals(LOMBOK_DEF)) {
					optionalFound = true;
					thisVarIsOptional = true;
				}
			}
			if(optionalFound && !thisVarIsOptional) return true;
		}
		return false;
	}

	private static String getDefaultFromAnnotation(JCAnnotation ann) {
		for(JCExpression jce : ann.args) {
			JCAssign assign = (JCAssign) jce;
			String value = assign.rhs.toString();
			value = value.substring(1, value.length()-1); // Removes the quotes from start and end.
			return value;
		}
		throw new IllegalArgumentException("You must provide a default value when using @Def "+
		                                   "with a non-java.util.Optional method parameter.");
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
		return varType.equals(JAVA_UTIL_OPTIONAL);
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
			if(value.equals("\\t")) {
				return (int) '\t';
			} else if(value.equals("\\b")) {
				return (int) '\b';
			} else if(value.equals("\\n")) {
				return (int) '\n';
			} else if(value.equals("\\f")) {
				return (int) '\f';
			} else if(value.equals("\\r")) {
				return (int) '\r';
			} else if(value.equals("\\\\")) {
				return (int) '\\';
			} else if(value.equals("\\\"")) {
				return (int) '"';
			} else if(value.equals("\\'")) {
				return (int) '\'';
			} else {
				return (int) value.charAt(0);
			}
		} else if(type.equals("java.lang.String")) {
			return value;
		} else throw new IllegalArgumentException("@Def can only be used on non-char primitives, Strings, and Optionals.");
	}

	private static boolean nodeIsTheFirstDefAnnotationInParams(JavacNode paramNode) {
		JCMethodDecl methodDecl = getJcMethodDeclFromNode(paramNode.up());
		List<JCVariableDecl> methodParams = methodDecl.getParameters();
		for(JCVariableDecl var : methodParams) {
			for(JCAnnotation ann : var.getModifiers().getAnnotations()) {
				if(ann.type.toString().equals(LOMBOK_DEF)) {
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
