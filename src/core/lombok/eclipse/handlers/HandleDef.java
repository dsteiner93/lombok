package lombok.eclipse.handlers;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.CharLiteral;
import org.eclipse.jdt.internal.compiler.ast.ConditionalExpression;
import org.eclipse.jdt.internal.compiler.ast.DoubleLiteral;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FalseLiteral;
import org.eclipse.jdt.internal.compiler.ast.FloatLiteral;
import org.eclipse.jdt.internal.compiler.ast.Literal;
import org.eclipse.jdt.internal.compiler.ast.MemberValuePair;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NameReference;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.StringLiteral;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TrueLiteral;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.mangosdk.spi.ProviderFor;

import lombok.Def;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.eclipse.Eclipse;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;

/**
 * Handles the {@code lombok.Def} annotation for eclipse.
 *
 * May not work correctly for java versions prior to 8 without some tweaks.
 */
@ProviderFor(EclipseAnnotationHandler.class)
public class HandleDef extends EclipseAnnotationHandler<Def> {

	private static final String VOID = "void";
	private static final String DEF = "Def";
	private static final String LOMBOK_DEF = "lombok.Def";
	private static final String OPTIONAL = "Optional";
	private static final String JAVA_UTIL_OPTIONAL = "java.util.Optional";
	private static final String MAP_NAME = "paramsMap";

	@Override public void handle(AnnotationValues<Def> annotation, Annotation ast, EclipseNode annotationNode) {
		EclipseNode paramNode = annotationNode.up();
		switch(paramNode.getKind()) {
			case ARGUMENT:
				List<MethodDeclaration> methodsToInject = createMethodsForOptionals(paramNode);
				for(MethodDeclaration methodToInject : methodsToInject) {
					// From the perspective of the method parameter, the class is two levels up.
					// First level up is the method, then the class.
					EclipseHandlerUtil.injectMethod(paramNode.up().up(), methodToInject);
				}
				return;
			default:
				throw new IllegalArgumentException("@Def is only valid on method arguments.");
		}
	}

	private List<MethodDeclaration> createMethodsForOptionals(EclipseNode paramNode) {
		List<MethodDeclaration> methodsToInject = new java.util.ArrayList<MethodDeclaration>();
		MethodDeclaration methodDecl = getMethodDeclFromNode(paramNode.up());

		if(paramNode.getKind() != Kind.ARGUMENT) {
			throw new IllegalArgumentException("Expected annotation to be applied to parameter.");
		}
		if(!nodeIsTheFirstDefAnnotationInParams(paramNode)) {
			// Skip creating extra methods for all but the first Def annotation, since they've already been created.
			return new ArrayList<MethodDeclaration>();
		}
		if(nonOptionalComesAfterOptional(methodDecl)) {
			throw new IllegalArgumentException("Non-optional params cannot come after @Def params.");
		}

		Argument[] methodArgs = methodDecl.arguments;
		List<Argument> requiredArguments = new ArrayList<Argument>();
		Map<Argument, String> optionalArgumentToDefaultValueMap = new LinkedHashMap<Argument, String>();
		for(Argument arg : methodArgs) {
			boolean optional = false;
			if(arg != null && arg.annotations != null) {
				for(Annotation ann : arg.annotations) {
					if(ann != null && isDefAnnotation(ann)) {
						optional = true;
						if(isAJavaOptional(arg)) {
							// No default string needed for optionals, default is always Optional.empty().
							optionalArgumentToDefaultValueMap.put(arg, "");
						} else {
							optionalArgumentToDefaultValueMap.put(arg, getDefaultValueFromAnnotation(ann));
						}
					}
				}
			}
			if(!optional) requiredArguments.add(arg);
		}

		MethodDeclaration methodWithoutDefaults = getMethodWithoutDefaults(methodDecl, paramNode, requiredArguments,
		                                                                   optionalArgumentToDefaultValueMap);
		MethodDeclaration methodWithMap = getMethodWithMap(methodDecl, paramNode, requiredArguments,
		                                                   optionalArgumentToDefaultValueMap);
		methodsToInject.add(methodWithoutDefaults);
		methodsToInject.add(methodWithMap);
		return methodsToInject;
	}

	/*
	 * Creates a version of the method without the default parameters in the signature.
	 * e.g. foo(String x, String y, @Def("2") int z) {}
	 * will have foo(String x, String y) { foo(x, y, 2); } auto-generated.
	 */
	private MethodDeclaration getMethodWithoutDefaults(MethodDeclaration originalMethod,
	                                                   EclipseNode paramNode,
	                                                   List<Argument> requiredArguments,
	                                                   Map<Argument, String> optionalArgumentToDefaultValueMap) {
		int sourceStart = paramNode.get().sourceStart;
		int sourceEnd = paramNode.get().sourceEnd;
		long posNum = (long)sourceStart << 32 | sourceEnd;
		Expression emptyOptional = getEmptyOptionalExpression(originalMethod);
		TypeDeclaration classDecl = (TypeDeclaration) paramNode.up().up().get();
		TypeReference methodReturnType = originalMethod.returnType;

		List<Argument> newArgs = new ArrayList<Argument>();
		for(Argument a : requiredArguments) {
			newArgs.add(new Argument(a.name, posNum, a.type, a.modifiers));
		}

		List<Expression> innerMethodParams = new ArrayList<Expression>();
		// First, add the required argument names.
		for(Argument arg : requiredArguments) {
			innerMethodParams.add(new SingleNameReference(arg.name, posNum));
		}
		// Then create expressions for all the default values.
		for(Map.Entry<Argument, String> entry : optionalArgumentToDefaultValueMap.entrySet()) {
			Argument arg = entry.getKey();
			String defaultValue = entry.getValue();
			String type = getTypeAsString(arg);
			Expression defaultValueExpression;
			if(isAJavaOptional(arg)) {
				defaultValueExpression = emptyOptional;
			} else {
				defaultValueExpression = getLiteral(type, defaultValue, sourceStart, sourceEnd, paramNode.get());
			}
			if(type.equals("short") || type.equals("byte")) {
				// Compiler complains if an int literal is passed for a short or byte.
				defaultValueExpression = new CastExpression(defaultValueExpression, arg.type);
			}
			innerMethodParams.add(defaultValueExpression);
		}

		MessageSend innerMethod = new MessageSend();
		innerMethod.arguments = innerMethodParams.toArray(new Expression[innerMethodParams.size()]);
		innerMethod.selector = originalMethod.selector;
		innerMethod.sourceStart = sourceStart;
		innerMethod.sourceEnd = sourceEnd;

		if(originalMethod.isStatic()) {
			Expression classRef = createQualifiedNameReference(String.valueOf(classDecl.name), originalMethod);
			innerMethod.receiver = classRef;
		} else {
			ThisReference thisReference = new ThisReference(sourceStart, sourceEnd);
			innerMethod.receiver = thisReference;
		}

		Statement methodBody;
		if(methodReturnType.toString().equals(VOID)) {
			methodBody = innerMethod;
		} else {
			methodBody = new ReturnStatement(innerMethod, sourceStart, sourceEnd);
		}

		MethodDeclaration newMethod = new MethodDeclaration(originalMethod.compilationResult);
		newMethod.annotations = originalMethod.annotations;
		newMethod.typeParameters = originalMethod.typeParameters;
		newMethod.selector = originalMethod.selector;
		newMethod.thrownExceptions = originalMethod.thrownExceptions;
		newMethod.returnType = methodReturnType;
		newMethod.modifiers = originalMethod.modifiers;
		newMethod.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		newMethod.arguments = newArgs.toArray(new Argument[newArgs.size()]);
		newMethod.bodyStart = originalMethod.sourceStart;
		newMethod.bodyEnd = originalMethod.sourceEnd;
		newMethod.declarationSourceStart = originalMethod.sourceStart;
		newMethod.declarationSourceEnd = originalMethod.sourceEnd;
		newMethod.statements = new Statement[] { methodBody };
		return newMethod;
	}

	/*
	 * Creates a version of the method with the default params passed in as a Map<String, Object>.
	 * e.g. foo(String x, @Def("2") int y) {}
	 * will auto-generate
	 * foo(String x, Map<String, Object> paramsMap) {
	 *     foo(x, paramsMap.containsKey("y") ? (int) paramsMap.get("y") : 2);
	 * }
	 */
	private MethodDeclaration getMethodWithMap(MethodDeclaration originalMethod,
                                               EclipseNode paramNode,
                                               List<Argument> requiredArguments,
                                               Map<Argument, String> optionalArgumentToDefaultValueMap) {
		int sourceStart = paramNode.get().sourceStart;
		int sourceEnd = paramNode.get().sourceEnd;
		long posNum = (long)sourceStart << 32 | sourceEnd;
		Expression emptyOptional = getEmptyOptionalExpression(originalMethod);
		TypeDeclaration classDecl = (TypeDeclaration) paramNode.up().up().get();
		TypeReference methodReturnType = originalMethod.returnType;

		// Create the java.util.Map<String, Object> for the outer method signature.
		char[] java = "java".toCharArray();
		char[] util = "util".toCharArray();
		char[][] javaUtilMap = new char[][] { java, util, "Map".toCharArray() };
		TypeReference stringRef = new SingleTypeReference("String".toCharArray(), 0L);
		TypeReference objectRef = new SingleTypeReference("Object".toCharArray(), 0L);
		TypeReference[] mapGenerics = new TypeReference[] { stringRef, objectRef };
		// Two nulls in array because java and util have no generics, only Map.
		TypeReference[][] allGenerics = new TypeReference[][] { null, null, mapGenerics };
		long[] posNumArr = new long[] { posNum };
		TypeReference javaUtilMapRef = new ParameterizedQualifiedTypeReference(javaUtilMap, allGenerics, 0, posNumArr);
		Argument mapArgument = new Argument(MAP_NAME.toCharArray(), posNum, javaUtilMapRef, Modifier.FINAL);

		List<Argument> newArgs = new ArrayList<Argument>();
		for(Argument a : requiredArguments) {
			newArgs.add(new Argument(a.name, posNum, a.type, a.modifiers));
		}
		newArgs.add(mapArgument);

		// Create the inner method with all optional parameters passed in as a Map<String, Object>.
		List<Expression> innerMethodParamsForMapMethod = new ArrayList<Expression>();
		// First, get a name reference to the required argument names.
		for(Argument arg : requiredArguments) {
			innerMethodParamsForMapMethod.add(new SingleNameReference(arg.name, posNum));
		}
		// For each of the optional params, we will make a ConditionalExpression (OperatorExpression) of the form
		// map.containsKey(argName) ? (TypeCast) map.get(argName) : defaultValue
		// and add that ConditionalExpression to the inner method params.
		for(Map.Entry<Argument, String> entry : optionalArgumentToDefaultValueMap.entrySet()) {
			Argument arg = entry.getKey();
			String defaultValue = entry.getValue();
			String type = getTypeAsString(arg);
			String argName = String.valueOf(arg.name);
			StringLiteral argNameLiteral1 = new StringLiteral(argName.toCharArray(), sourceStart, sourceEnd, 0);
			StringLiteral argNameLiteral2 = new StringLiteral(argName.toCharArray(), sourceStart, sourceEnd, 0);
			// It seems strange to create the same String literal twice, but this is actually essential for the
			// newer Eclipse versions. If you pass the same literal twice it will try to resolve the same literal
			// twice and display an error. Previous versions would auto-unresolve, but the behavior was changed.
			// https://bugs.eclipse.org/bugs/show_bug.cgi?id=426996
			// That's the downside of these "hacks", they are brittle to changes to unadvertised parts of compilers.

			// First make the conditional expression, map.containsKey(argName)
			MessageSend containsKey = new MessageSend();
			containsKey.receiver = new SingleNameReference(MAP_NAME.toCharArray(), posNum);
			containsKey.arguments = new Expression[] { argNameLiteral1 };
			containsKey.selector = "containsKey".toCharArray();

			// Then make (TypeCast) map.get(argName)
			MessageSend get = new MessageSend();
			get.receiver = new SingleNameReference(MAP_NAME.toCharArray(), posNum);
			get.arguments = new Expression[] { argNameLiteral2 };
			get.selector = "get".toCharArray();
			CastExpression typeCast = new CastExpression(get, arg.type);

			// Finally, get the default value expression
			Expression defaultValueExpression;
			if(isAJavaOptional(arg)) {
				defaultValueExpression = emptyOptional;
			} else {
				defaultValueExpression = getLiteral(type, defaultValue, sourceStart, sourceEnd, paramNode.get());
			}
			if(type.equals("short") || type.equals("byte")) {
				// Compiler complains if an int literal is passed for a short or byte.
				defaultValueExpression = new CastExpression(defaultValueExpression, arg.type);
			}

			Expression conditionalExpression = new ConditionalExpression(containsKey, typeCast, defaultValueExpression);
			innerMethodParamsForMapMethod.add(conditionalExpression);
		}

		MessageSend innerMethod = new MessageSend();
		innerMethod.arguments = innerMethodParamsForMapMethod.toArray(new Expression[innerMethodParamsForMapMethod.size()]);
		innerMethod.selector = originalMethod.selector;
		innerMethod.sourceStart = sourceStart;
		innerMethod.sourceEnd = sourceEnd;

		if(originalMethod.isStatic()) {
			Expression classRef = createQualifiedNameReference(String.valueOf(classDecl.name), originalMethod);
			innerMethod.receiver = classRef;
		} else {
			ThisReference thisReference = new ThisReference(sourceStart, sourceEnd);
			innerMethod.receiver = thisReference;
		}

		Statement methodBody;
		if(methodReturnType.toString().equals(VOID)) {
			methodBody = innerMethod;
		} else {
			methodBody = new ReturnStatement(innerMethod, sourceStart, sourceEnd);
		}

		MethodDeclaration newMethod = new MethodDeclaration(originalMethod.compilationResult);
		newMethod.annotations = originalMethod.annotations;
		newMethod.typeParameters = originalMethod.typeParameters;
		newMethod.selector = originalMethod.selector;
		newMethod.thrownExceptions = originalMethod.thrownExceptions;
		newMethod.returnType = methodReturnType;
		newMethod.modifiers = originalMethod.modifiers;
		newMethod.bits |= Eclipse.ECLIPSE_DO_NOT_TOUCH_FLAG;
		newMethod.arguments = newArgs.toArray(new Argument[newArgs.size()]);
		newMethod.bodyStart = originalMethod.sourceStart;
		newMethod.bodyEnd = originalMethod.sourceEnd;
		newMethod.declarationSourceStart = originalMethod.sourceStart;
		newMethod.declarationSourceEnd = originalMethod.sourceEnd;
		newMethod.statements = new Statement[] { methodBody };
		return newMethod;
	}

	private static boolean nonOptionalComesAfterOptional(MethodDeclaration methodDecl) {
		boolean optionalFound = false;
		for(Argument arg : methodDecl.arguments) {
			boolean thisVarIsOptional = false;
			if(arg != null && arg.annotations != null) {
				for(Annotation ann : arg.annotations) {
					if(ann != null && isDefAnnotation(ann)) {
						optionalFound = true;
						thisVarIsOptional = true;
					}
				}
			}
			if(optionalFound && !thisVarIsOptional) return true;
		}
		return false;
	}

	private static String getDefaultValueFromAnnotation(Annotation ann) {
		for(MemberValuePair mvp : ann.memberValuePairs()) {
			// Currently Def args are all String literals, i.e. not NumberLiterals.
			// In the future, if Def supported other literals we would parse them from the constant here.
			Literal literal = (Literal) mvp.value;
			String value = literal.toString().replaceAll("\"", "");
			return value;
		}
		throw new IllegalArgumentException("You must provide a default value when using @Def "+
		                                   "with a non-java.util.Optional method parameter.");
	}

	private static MethodDeclaration getMethodDeclFromNode(EclipseNode methodNode) {
		return (MethodDeclaration) methodNode.get();
	}

	private static MessageSend getEmptyOptionalExpression(ASTNode source) {
		NameReference optional = createQualifiedNameReference("java.util.Optional", source);
		MessageSend optionalEmptyInvocation = new MessageSend();
		optionalEmptyInvocation.arguments = new Expression[0];
		optionalEmptyInvocation.receiver = optional;
		optionalEmptyInvocation.selector = "empty".toCharArray();
		return optionalEmptyInvocation;
	}

	private static NameReference createQualifiedNameReference(String name, ASTNode source) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long)pS << 32 | pE;
		char[][] nameTokens = Eclipse.fromQualifiedName(name);
		long[] pos = new long[nameTokens.length];
		Arrays.fill(pos, p);
		QualifiedNameReference nameReference = new QualifiedNameReference(nameTokens, pos, pS, pE);
		nameReference.statementEnd = pE;
		return nameReference;
	}

	private static Literal getLiteral(String type, String value, int sourceStart, int sourceEnd, ASTNode source) {
		if(type.equals("int")) {
			return EclipseHandlerUtil.makeIntLiteral(value.toCharArray(), source);
		} else if(type.equals("double")) {
			return new DoubleLiteral(value.toCharArray(), sourceStart, sourceEnd);
		} else if(type.equals("float")) {
			return new FloatLiteral(value.toCharArray(), sourceStart, sourceEnd);
		} else if(type.equals("short")) {
			return EclipseHandlerUtil.makeIntLiteral(value.toCharArray(), source);
		} else if(type.equals("long")) {
			// If no 'L' is appended to the literal, Eclipse will chop off the last digit.
			if(!value.endsWith("L")) value = value+"L";
			return EclipseHandlerUtil.makeLongLiteral(value.toCharArray(), source);
		} else if(type.equals("byte")) {
			return EclipseHandlerUtil.makeIntLiteral(value.toCharArray(), source);
		} else if(type.equals("boolean")) {
			boolean isTrue = Boolean.parseBoolean(value);
			return isTrue ? new TrueLiteral(sourceStart, sourceEnd) : new FalseLiteral(sourceStart, sourceEnd);
		} else if(type.equals("char")) {
			// Eclipse expects the char to start with ' and end with ', e.g. 'c'
			// Except for escape chars, like '\t'.
			if(value.length() == 1) {
				char[] literal = new char[] { '\'', value.toCharArray()[0], '\'' };
				return new CharLiteral(literal, sourceStart, sourceEnd);
			} else {
				char[] literal = new char[] { '\'', value.toCharArray()[0], value.toCharArray()[1], '\'' };
				return new CharLiteral(literal, sourceStart, sourceEnd);
			}
		} else if(type.equals("String")) {
			return new StringLiteral(value.toCharArray(), sourceStart, sourceEnd, 0);
		} else {
			throw new IllegalArgumentException("@Def can only be used on primitives, Strings, and Optionals.");
		}
	}

	private static boolean nodeIsTheFirstDefAnnotationInParams(EclipseNode paramNode) {
		MethodDeclaration methodDecl = getMethodDeclFromNode(paramNode.up());
		for(Argument arg : methodDecl.arguments) {
			if(arg != null && arg.annotations != null) {
				for(Annotation ann : arg.annotations) {
					if(ann != null && isDefAnnotation(ann)) {
						return String.valueOf(arg.name).equals(paramNode.getName().toString());
					}
				}
			}
		}
		return false;
	}

	private static String getTypeAsString(Argument arg) {
		String type = getStringFromCharDoubleArray(arg.type.getTypeName());
		return type;
	}

	private static String getTypeAsString(Annotation ann) {
		String type = getStringFromCharDoubleArray(ann.type.getTypeName());
		return type;
	}

	private static String getStringFromCharDoubleArray(char[][] arr) {
		StringBuilder sb = new StringBuilder();
		for(int i=0; i<arr.length-1; i++) {
			sb.append(String.valueOf(arr[i]));
			sb.append(".");
		}
		String type = String.valueOf(arr[arr.length-1]);
		sb.append(type);
		return sb.toString();
	}

	private static boolean isDefAnnotation(Annotation ann) {
		String type = getTypeAsString(ann);
		return type.equals(LOMBOK_DEF) || type.equals(DEF);
	}

	private static boolean isAJavaOptional(Argument arg) {
		// The reason we have to rely on all these toStrings, rather than getting the true
		// qualified type names is because Eclipse seems to require a scope object to get the full
		// qualified name (e.g. "java.util.Optional" instead of just "Optional").
		// Unfortunately, as far as I could tell the scope objects are always null when lombok sees them.
		String varType = getTypeAsString(arg);
		return varType.equals(JAVA_UTIL_OPTIONAL) || varType.equals(OPTIONAL);
	}

}
