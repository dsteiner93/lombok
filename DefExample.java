import lombok.Def;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

public class DefExample {

	public static void main(String[] args) {
		System.out.println("All defaults:");
		foo(1, 2);
		System.out.println();

		Map<String, Object> paramsMap = new HashMap<String, Object>();
		paramsMap.put("d", "not default!");
		paramsMap.put("e", false);
		System.out.println("Overriding a couple defaults:");
		foo(1, 2, paramsMap);
		System.out.println();

		System.out.println("Passing all parameters:");
		Optional<String> g = Optional.of("not default!");
		foo(1, 2, 'c', "4", false, 5.0, g, (byte) 1, (short) 2, 3);
	}

	private static int foo(int a, int b, @Def("z") char c, @Def("default") String d, @Def("true") boolean e, @Def("99.9") double f,
	                        @Def Optional<String> g, @Def("10") byte h, @Def("11") short i, @Def("12") int j) {
		System.out.println("a: "+a);
		System.out.println("b: "+b);
		System.out.println("c: "+c);
		System.out.println("d: "+d);
		System.out.println("e: "+e);
		System.out.println("f: "+f);
		System.out.println("g: "+g.orElse("was not passed"));
		System.out.println("h: "+h);
		System.out.println("i: "+i);
		System.out.println("j: "+j);
		return 1;
	}

}

