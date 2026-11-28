import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Searches a Gboard APK for members by shape, and prints the whole member index.
 *
 * {@code ProposeRetarget} starts from a binding and proposes where it went. This is the other
 * direction: it starts from the shape a patch needs - "a method with these parameters", "a field of
 * this type" - and lists the members that have it, across all 42k classes. That is what a port
 * needs when the old owner class was renamed away and the new one has to be found from scratch.
 *
 * Usage:
 *   DexQuery <gboard.apk> index
 *   DexQuery <gboard.apk> sig <returnType> [<parameterType>...]
 *   DexQuery <gboard.apk> paramsig [<parameterType>...]
 *   DexQuery <gboard.apk> field <type> [<name>]
 *
 * {@code index} prints one line per declared field and method, so a whole release can be grepped:
 *
 *   METHOD <owner>-><name>(<params>)<return> regs=<n> access=0x<flags>
 *   FIELD  <owner>-><name>:<type> access=0x<flags>
 *
 * Exits 0 when something matched, 1 when nothing did, and 2 on a usage or read error, so an empty
 * result is distinguishable from a failed run.
 */
public final class DexQuery {

    private static final String USAGE = "usage: DexQuery <gboard.apk> index"
            + " | sig <returnType> [<parameterType>...]"
            + " | paramsig [<parameterType>...]"
            + " | field <type> [<name>]";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(USAGE);
            System.exit(2);
        }
        File apk = new File(args[0]);
        if (!apk.isFile()) {
            System.err.println("error: APK not found: " + apk);
            System.exit(2);
        }
        String command = args[1];
        List<ClassDef> classes = readClasses(apk);
        PrintStream out = System.out;
        int matched;
        switch (command) {
            case "index" -> {
                if (args.length != 2) {
                    System.err.println(USAGE);
                    System.exit(2);
                }
                matched = index(classes, out);
            }
            case "sig" -> {
                if (args.length < 3) {
                    System.err.println(USAGE);
                    System.exit(2);
                }
                matched = sig(classes, args[2], parameters(args, 3), out);
            }
            case "paramsig" -> {
                matched = sig(classes, null, parameters(args, 2), out);
            }
            case "field" -> {
                if (args.length < 3 || args.length > 4) {
                    System.err.println(USAGE);
                    System.exit(2);
                }
                matched = field(classes, args[2], args.length == 4 ? args[3] : null, out);
            }
            default -> {
                System.err.println(USAGE);
                System.exit(2);
                return;
            }
        }
        out.flush();
        System.exit(matched == 0 ? 1 : 0);
    }

    private static List<String> parameters(String[] args, int from) {
        List<String> parameters = new ArrayList<>();
        for (int index = from; index < args.length; index++) {
            parameters.add(args[index]);
        }
        return parameters;
    }

    private static int index(List<ClassDef> classes, PrintStream out) {
        int count = 0;
        for (ClassDef classDef : classes) {
            for (Field field : classDef.getFields()) {
                out.println("FIELD  " + classDef.getType() + "->" + field.getName() + ":"
                        + field.getType() + " access=0x"
                        + Integer.toHexString(field.getAccessFlags()));
                count++;
            }
            for (Method method : classDef.getMethods()) {
                out.println("METHOD " + descriptor(method) + " regs=" + registerCount(method)
                        + " access=0x" + Integer.toHexString(method.getAccessFlags()));
                count++;
            }
        }
        return count;
    }

    private static int sig(
            List<ClassDef> classes,
            String returnType,
            List<String> parameters,
            PrintStream out
    ) {
        int count = 0;
        for (ClassDef classDef : classes) {
            for (Method method : classDef.getMethods()) {
                if (!sameParameters(method, parameters)) {
                    continue;
                }
                if (returnType != null && !method.getReturnType().equals(returnType)) {
                    continue;
                }
                out.println(descriptor(method) + " regs=" + registerCount(method)
                        + " access=0x" + Integer.toHexString(method.getAccessFlags()));
                count++;
            }
        }
        return count;
    }

    private static int field(List<ClassDef> classes, String type, String name, PrintStream out) {
        int count = 0;
        for (ClassDef classDef : classes) {
            for (Field field : classDef.getFields()) {
                if (!field.getType().equals(type)) {
                    continue;
                }
                if (name != null && !field.getName().equals(name)) {
                    continue;
                }
                out.println(classDef.getType() + "->" + field.getName() + ":" + field.getType()
                        + " access=0x" + Integer.toHexString(field.getAccessFlags()));
                count++;
            }
        }
        return count;
    }

    private static boolean sameParameters(Method method, List<String> parameters) {
        int index = 0;
        for (CharSequence parameter : method.getParameterTypes()) {
            if (index >= parameters.size() || !parameters.get(index).contentEquals(parameter)) {
                return false;
            }
            index++;
        }
        return index == parameters.size();
    }

    private static String descriptor(Method method) {
        StringBuilder descriptor = new StringBuilder();
        descriptor.append(method.getDefiningClass()).append("->").append(method.getName()).append("(");
        for (CharSequence parameter : method.getParameterTypes()) {
            descriptor.append(parameter);
        }
        return descriptor.append(")").append(method.getReturnType()).toString();
    }

    private static int registerCount(Method method) {
        return method.getImplementation() == null ? 0 : method.getImplementation().getRegisterCount();
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
