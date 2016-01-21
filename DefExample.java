import lombok.Def;
import java.util.Optional;
import com.google.common.collect.ImmutableMap;

public class DefExample {

	public static void main(String[] args) {
		System.out.println("All defaults:");
		foo(1, 2);
		System.out.println();

		System.out.println("Overriding a couple defaults:");
		foo(1, 2, ImmutableMap.of("d", "not default!", "e", false));
		System.out.println();

		System.out.println("Passing all parameters:");
		Optional<String> g = Optional.of("not default!");
		foo(1, 2, 3, "4", false, 5.0, g);
	}

	private static void foo(int a, int b, @Def("99") int c, @Def("default") String d, @Def("true") boolean e, @Def("99.9") double f,
				@Def Optional<String> g) {
		System.out.println("a: "+a);
		System.out.println("b: "+b);
		System.out.println("c: "+c);
		System.out.println("d: "+d);
		System.out.println("e: "+e);
		System.out.println("f: "+f);
		System.out.println("g: "+g.orElse("was not passed"));
	}

}

