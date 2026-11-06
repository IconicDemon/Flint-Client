package com.flintclient.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Flint's mixin configs' plugin. On the Minecraft version a jar was built for it changes nothing. On a newer one (the
 * launcher gave Flint's newest runtime to, say, 26.4) every Flint hook merged into a Minecraft class is guarded: when it
 * throws (typically a Minecraft method or field it calls is gone) it is switched off for the rest of the run and Minecraft
 * goes on as if the hook weren't there: a hook that changes a value hands the value back unchanged, one that wraps a call
 * makes the original call, one that decides whether a call happens lets it happen. The game keeps running with that one
 * feature off instead of crashing. See {@link HookFailures} and {@link FlintPreLaunch}.
 */
public final class FlintMixinPlugin implements IMixinConfigPlugin {
	private static final String GUARD = "com/flintclient/client/HookFailures";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	private static final String MARK = "$" + FlintClient.MOD_ID + "$";

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		if (!FlintPreLaunch.untested() || (targetClass.access & Opcodes.ACC_INTERFACE) != 0) {
			return;
		}
		for (MethodNode method : targetClass.methods) {
			if (method.name.contains(MARK) && method.instructions.size() > 0 && !guarded(method)) {
				guard(targetClassName, method);
			}
		}
	}

	/** Already guarded (a second Flint mixin on the same class is applied later). */
	private static boolean guarded(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(GUARD)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code if (HookFailures.off(id)) <fallback>; try { <hook> } catch (Throwable t) { HookFailures.failed(id, t); <fallback> }}.
	 * Frames are recomputed when Mixin writes the class.
	 */
	private static void guard(String targetClassName, MethodNode method) {
		int id = HookFailures.register(targetClassName.substring(targetClassName.lastIndexOf('.') + 1) + "." + method.name);
		LabelNode start = new LabelNode();
		LabelNode end = new LabelNode();
		LabelNode handler = new LabelNode();

		InsnList head = new InsnList();
		head.add(push(id));
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "off", "(I)Z", false));
		head.add(new JumpInsnNode(Opcodes.IFEQ, start));
		head.add(fallback(method));
		head.add(start);
		method.instructions.insert(head);

		InsnList tail = new InsnList();
		tail.add(end);
		tail.add(handler);
		int slot = method.maxLocals;
		tail.add(new VarInsnNode(Opcodes.ASTORE, slot));
		tail.add(push(id));
		tail.add(new VarInsnNode(Opcodes.ALOAD, slot));
		tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "failed", "(ILjava/lang/Throwable;)V", false));
		tail.add(fallback(method));
		method.instructions.add(tail);
		if (method.tryCatchBlocks == null) {
			method.tryCatchBlocks = new ArrayList<>();
		}
		method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
		method.maxLocals = slot + 1;
		method.maxStack = Math.max(method.maxStack, 8);
	}

	/** Minecraft as if the hook weren't there, then return. */
	private static InsnList fallback(MethodNode method) {
		InsnList out = new InsnList();
		Type returns = Type.getReturnType(method.desc);
		Type[] args = Type.getArgumentTypes(method.desc);
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		String kind = method.name.substring(0, Math.max(0, method.name.indexOf('$')));
		if (returns.getSort() == Type.VOID) {
			out.add(new InsnNode(Opcodes.RETURN));
			return out;
		}
		if (kind.equals("wrapWithCondition") && returns.getSort() == Type.BOOLEAN) {
			out.add(new InsnNode(Opcodes.ICONST_1));
			out.add(new InsnNode(Opcodes.IRETURN));
			return out;
		}
		int operation = -1;
		for (int i = 0; i < args.length; i++) {
			if (args[i].getDescriptor().equals(OPERATION)) {
				operation = i;
				break;
			}
		}
		if (operation >= 0) {
			// original.call(arguments before it...)
			out.add(new VarInsnNode(Opcodes.ALOAD, local(args, operation, isStatic)));
			out.add(push(operation));
			out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
			for (int i = 0; i < operation; i++) {
				out.add(new InsnNode(Opcodes.DUP));
				out.add(push(i));
				out.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), local(args, i, isStatic)));
				box(out, args[i]);
				out.add(new InsnNode(Opcodes.AASTORE));
			}
			out.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OPERATION.substring(1, OPERATION.length() - 1), "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
			unbox(out, returns);
			out.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
			return out;
		}
		// A hook that changes a value gets that value first: hand it back unchanged.
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals(returns)) {
				out.add(new VarInsnNode(returns.getOpcode(Opcodes.ILOAD), local(args, i, isStatic)));
				out.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
				return out;
			}
		}
		out.add(zero(returns));
		out.add(new InsnNode(returns.getOpcode(Opcodes.IRETURN)));
		return out;
	}

	private static int local(Type[] args, int index, boolean isStatic) {
		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < index; i++) {
			slot += args[i].getSize();
		}
		return slot;
	}

	private static AbstractInsnNode push(int value) {
		return value >= -1 && value <= 5 ? new InsnNode(Opcodes.ICONST_0 + value)
			: value <= Short.MAX_VALUE ? new IntInsnNode(value <= Byte.MAX_VALUE ? Opcodes.BIPUSH : Opcodes.SIPUSH, value) : new LdcInsnNode(value);
	}

	private static AbstractInsnNode zero(Type type) {
		return switch (type.getSort()) {
			case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
			case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
			case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
			case Type.OBJECT, Type.ARRAY -> new InsnNode(Opcodes.ACONST_NULL);
			default -> new InsnNode(Opcodes.ICONST_0);
		};
	}

	private static void box(InsnList out, Type type) {
		Type boxed = boxed(type);
		if (boxed != null) {
			out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, boxed.getInternalName(), "valueOf", "(" + type.getDescriptor() + ")" + boxed.getDescriptor(), false));
		}
	}

	private static void unbox(InsnList out, Type type) {
		Type boxed = boxed(type);
		if (boxed == null) {
			out.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
			return;
		}
		out.add(new TypeInsnNode(Opcodes.CHECKCAST, boxed.getInternalName()));
		out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, boxed.getInternalName(), type.getClassName() + "Value", "()" + type.getDescriptor(), false));
	}

	private static Type boxed(Type type) {
		return switch (type.getSort()) {
			case Type.BOOLEAN -> Type.getObjectType("java/lang/Boolean");
			case Type.BYTE -> Type.getObjectType("java/lang/Byte");
			case Type.CHAR -> Type.getObjectType("java/lang/Character");
			case Type.SHORT -> Type.getObjectType("java/lang/Short");
			case Type.INT -> Type.getObjectType("java/lang/Integer");
			case Type.LONG -> Type.getObjectType("java/lang/Long");
			case Type.FLOAT -> Type.getObjectType("java/lang/Float");
			case Type.DOUBLE -> Type.getObjectType("java/lang/Double");
			default -> null;
		};
	}
}
