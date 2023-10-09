package fr.umlv.smalljs.astinterp;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;
import fr.umlv.smalljs.rt.JSObject.Invoker;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.util.stream.Collectors.joining;

public class ASTInterpreter {
  private static JSObject asJSObject(Object value, int lineNumber) {
    if (!(value instanceof JSObject jsObject)) {
      throw new Failure("at line " + lineNumber + ", type error " + value + " is not a JSObject");
    }
    return jsObject;
  }

  private static Object call(JSObject env, Object receiver, Object maybeFunction, List<Expr> args, int lineNumber) {
    if (!(maybeFunction instanceof JSObject jsObject)) {
      throw new Failure(maybeFunction + " is not callable at line " + lineNumber);
    }
    var values = args.stream()
      .map(a -> visit(a, env))
      .toArray();
    return jsObject.invoke(receiver, values);
  }

  static Object visit(Expr expression, JSObject env) {
    return switch (expression) {

      case Block(List<Expr> instructions, int lineNumber) -> {
        instructions.forEach(instr -> visit(instr, env));
        yield UNDEFINED;
      }

      case Literal<?>(Object value, int lineNumber) -> value;

      case FunCall(Expr qualifier, List<Expr> args, int lineNumber) -> call(env, UNDEFINED, visit(qualifier, env), args, lineNumber);

      case LocalVarAccess(String name, int lineNumber) -> env.lookup(name);

      case LocalVarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
        if (declaration && env.lookup(name) != UNDEFINED) {
          throw new Failure(name + " already defined at " + lineNumber);
        }
        env.register(name, visit(expr, env));
        yield UNDEFINED;
      }

      case Fun(Optional<String> optName, List<String> parameters, Block body, int lineNumber) -> {
        var functionName = optName.orElse("lambda");
        Invoker invoker = (self, receiver, args) -> {
          if (args.length != parameters.size()) {
            throw new Failure("Invalid number of arguments for " + functionName + " at line " + lineNumber);
          }
          var localEnv = JSObject.newEnv(env);
          localEnv.register("this", receiver);
          // BUG WAS HERE, should have been localEnv not env
          // IntStream.range(0, parameters.size()).forEach(i -> env.register(parameters.get(i), args[i]));
          IntStream.range(0, parameters.size()).forEach(i -> localEnv.register(parameters.get(i), args[i]));
          try {
            return visit(body, localEnv);
          } catch (ReturnError returnError) {
            return returnError.getValue();
          }
        };

        var function = JSObject.newFunction(functionName, invoker);
        optName.ifPresent(name -> env.register(name, function));
        yield function;
      }
      case Return(Expr expr, int lineNumber) -> throw new ReturnError(visit(expr, env));

      case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
        var booleanValue = visit(condition, env);
        if (!(booleanValue instanceof Integer value)) {
          throw new Failure("Invalid boolean value");
        }
        var block = value == 0 ? falseBlock : trueBlock;
        yield visit(block, env);
      }

      case New(Map<String, Expr> initMap, int lineNumber) -> {
        var obj = JSObject.newObject(null);
        initMap.forEach((name, expr) -> obj.register(name, visit(expr, env)));
        yield obj;
      }
      case FieldAccess(Expr receiver, String name, int lineNumber) -> asJSObject(visit(receiver, env), lineNumber).lookup(name);

      case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
        asJSObject(visit(receiver, env), lineNumber).register(name, visit(expr, env));
        yield UNDEFINED;
      }

      case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
        var object = asJSObject(visit(receiver, env), lineNumber);
        var maybeFunction = object.lookup(name);
        yield call(env, object, maybeFunction, args, lineNumber);
      }
    };
  }

  @SuppressWarnings("unchecked")
  public static void interpret(Script script, PrintStream outStream) {
    JSObject globalEnv = JSObject.newEnv(null);
    Block body = script.body();
    globalEnv.register("global", globalEnv);
    globalEnv.register("print", JSObject.newFunction("print", (self, receiver, args) -> {
      System.err.println("print called with " + Arrays.toString(args));
      outStream.println(Arrays.stream(args).map(Object::toString).collect(joining(" ")));
      return UNDEFINED;
    }));
    globalEnv.register("+", JSObject.newFunction("+", (self, receiver, args) -> (Integer) args[0] + (Integer) args[1]));
    globalEnv.register("-", JSObject.newFunction("-", (self, receiver, args) -> (Integer) args[0] - (Integer) args[1]));
    globalEnv.register("/", JSObject.newFunction("/", (self, receiver, args) -> (Integer) args[0] / (Integer) args[1]));
    globalEnv.register("*", JSObject.newFunction("*", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));
    globalEnv.register("%", JSObject.newFunction("%", (self, receiver, args) -> (Integer) args[0] % (Integer) args[1]));

    globalEnv.register("==", JSObject.newFunction("==", (self, receiver, args) -> args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("!=", JSObject.newFunction("!=", (self, receiver, args) -> !args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("<", JSObject.newFunction("<", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
    globalEnv.register("<=", JSObject.newFunction("<=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
    globalEnv.register(">", JSObject.newFunction(">", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
    globalEnv.register(">=", JSObject.newFunction(">=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
    visit(body, globalEnv);
  }
}

