import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.TryBlock;
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.FieldOffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.InlineIndexInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload;
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.VerificationErrorInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.VtableIndexInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Prints the structural fingerprint a patch compares a method against.
 *
 * {@code GboardStructuralFingerprint.kt} hashes a method's opcodes, registers, references, literals
 * and try blocks, which is how a patch states "this is Google's stock body" without depending on
 * names. A port to a new release has to produce that value for the new stock body, and the only
 * honest way to get it is to run the same algorithm over the release's dex. This is that run.
 *
 * Usage: java -cp "libs/morphe-cli.jar" tools/bindings/StructuralFingerprint.java \
 *            <gboard.apk> '<class;>' <method> '<parameterTypes>' <returnType>
 *
 * Example:
 *   StructuralFingerprint <apk> 'Lkbl;' f 'Lkbf;Lkbo;I' V
 *
 * The canonical text and the SHA-256 are both printed, so a disagreement can be read instead of
 * guessed at. Exits 0 when the method was found, 1 when it was not, 2 on a usage or read error.
 */
public final class StructuralFingerprint {

    private static final String USAGE = "usage: StructuralFingerprint <gboard.apk> '<class;>' <method>"
            + " '<parameterTypes>' <returnType>";

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println(USAGE);
            System.exit(2);
        }
        File apk = new File(args[0]);
        if (!apk.isFile()) {
            System.err.println("error: APK not found: " + apk);
            System.exit(2);
        }
        String classType = args[1];
        String methodName = args[2];
        String parameters = args[3];
        String returnType = args[4];

        List<Method> matches = new ArrayList<>();
        for (ClassDef classDef : readClasses(apk)) {
            if (!classDef.getType().equals(classType)) {
                continue;
            }
            for (Method method : classDef.getMethods()) {
                if (method.getName().equals(methodName)
                        && String.join("", method.getParameterTypes()).equals(parameters)
                        && method.getReturnType().equals(returnType)) {
                    matches.add(method);
                }
            }
        }
        if (matches.isEmpty()) {
            System.err.println("not found: " + classType + "->" + methodName + "(" + parameters + ")"
                    + returnType);
            System.exit(1);
        }
        for (Method method : matches) {
            String canonical = canonical(method);
            System.out.println(classType + "->" + methodName + "(" + parameters + ")" + returnType);
            System.out.println("fingerprint=" + sha256(canonical));
            System.out.println(canonical);
        }
    }

    private static String canonical(Method method) {
        if (method.getImplementation() == null) {
            throw new IllegalArgumentException("No implementation: " + method.getName());
        }
        StringBuilder canonical = new StringBuilder("registers=")
                .append(method.getImplementation().getRegisterCount());
        int index = 0;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            canonical.append('\n').append(index++).append(':').append(instruction.getOpcode().name);
            if (instruction instanceof RegisterRangeInstruction range) {
                canonical.append("|range=").append(range.getStartRegister()).append(',')
                        .append(range.getRegisterCount());
            } else if (instruction instanceof FiveRegisterInstruction five) {
                canonical.append("|registers=").append(five.getRegisterCount()).append(',')
                        .append(five.getRegisterC()).append(',').append(five.getRegisterD())
                        .append(',').append(five.getRegisterE()).append(',')
                        .append(five.getRegisterF()).append(',').append(five.getRegisterG());
            } else if (instruction instanceof ThreeRegisterInstruction three) {
                canonical.append("|registers=").append(three.getRegisterA()).append(',')
                        .append(three.getRegisterB()).append(',').append(three.getRegisterC());
            } else if (instruction instanceof TwoRegisterInstruction two) {
                canonical.append("|registers=").append(two.getRegisterA()).append(',')
                        .append(two.getRegisterB());
            } else if (instruction instanceof OneRegisterInstruction one) {
                canonical.append("|register=").append(one.getRegisterA());
            }
            if (instruction instanceof ReferenceInstruction reference) {
                canonical.append("|reference=").append(reference.getReference());
            }
            if (instruction instanceof DualReferenceInstruction dual) {
                canonical.append("|reference2=").append(dual.getReference2());
            }
            if (instruction instanceof WideLiteralInstruction literal) {
                canonical.append("|literal=").append(literal.getWideLiteral());
            }
            if (instruction instanceof OffsetInstruction offset) {
                canonical.append("|offset=").append(offset.getCodeOffset());
            }
            if (instruction instanceof VerificationErrorInstruction verification) {
                canonical.append("|verification=").append(verification.getVerificationError());
            }
            if (instruction instanceof FieldOffsetInstruction fieldOffset) {
                canonical.append("|fieldOffset=").append(fieldOffset.getFieldOffset());
            }
            if (instruction instanceof VtableIndexInstruction vtable) {
                canonical.append("|vtableIndex=").append(vtable.getVtableIndex());
            }
            if (instruction instanceof InlineIndexInstruction inline) {
                canonical.append("|inlineIndex=").append(inline.getInlineIndex());
            }
            if (instruction instanceof SwitchPayload payload) {
                canonical.append("|switch=");
                for (SwitchElement element : payload.getSwitchElements()) {
                    canonical.append(element.getKey()).append('@').append(element.getOffset())
                            .append(',');
                }
            }
            if (instruction instanceof ArrayPayload payload) {
                canonical.append("|array=").append(payload.getElementWidth()).append(':');
                for (Number element : payload.getArrayElements()) {
                    canonical.append(element).append(',');
                }
            }
        }
        for (TryBlock<? extends com.android.tools.smali.dexlib2.iface.ExceptionHandler> tryBlock
                : method.getImplementation().getTryBlocks()) {
            canonical.append("\ntry=").append(tryBlock.getStartCodeAddress()).append(',')
                    .append(tryBlock.getCodeUnitCount()).append(':');
            for (com.android.tools.smali.dexlib2.iface.ExceptionHandler handler
                    : tryBlock.getExceptionHandlers()) {
                canonical.append(handler.getExceptionType()).append('@')
                        .append(handler.getHandlerCodeAddress()).append(',');
            }
        }
        return canonical.toString();
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private static List<ClassDef> readClasses(File apk) throws Exception {
        List<ClassDef> classes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".dex")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream stream = zip.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                DexBackedDexFile dex = DexBackedDexFile.fromInputStream(
                        Opcodes.getDefault(), new ByteArrayInputStream(bytes));
                for (ClassDef classDef : dex.getClasses()) {
                    classes.add(classDef);
                }
            }
        }
        return classes;
    }
}
