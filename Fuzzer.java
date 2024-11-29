import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class Fuzzer {

    private static final Random random = new Random();

    private static List<Mutator> mutators = new ArrayList<>();
    private static long attempt = 0;
    private static String commandToFuzz;
    private static boolean stopOnFirstFailure = true;

    private static final String DEFAULT_SEED_INPUT = "<html a=\"value\">...</html>";
    private static String seedInput = DEFAULT_SEED_INPUT;

    private static final int DEFAULT_PASSES = 64;
    private static int passes = DEFAULT_PASSES;

    private static final int DEFAULT_COUNT = 64;
    private static int count = DEFAULT_COUNT;


    private static final String MSG_USAGE =
            "java Fuzzer.java [options] <command_to_fuzz>\n" +
                    "Options:\n" +
                    "  -s <seed_input>      Seed input to start fuzzing from (default: \"" + DEFAULT_SEED_INPUT + "\")\n" +
                    "  -n <count>           Number of duplicated seed inputs to start with (default: " + DEFAULT_COUNT + ")\n" +
                    "  -p <passes>          Maximum number of mutations applied to each seed input (default: " + DEFAULT_PASSES + ")\n" +
                    "  -f                   Do not stop fuzzing on first failure\n" +
                    "  -m <mutators>        Comma-separated list of mutators to use and their respective share\n" +
                    "                       Mutators:\n" +
                    "                         " + Mutator.InsertRandomAsciiSymbolMutator.name + "\n" +
                    "                         " + Mutator.InsertRandomLetterOrDigitMutator.name + "\n" +
                    "                         " + Mutator.InsertRandomStringMutator.name + "\n" +
                    "                         " + Mutator.DeleteRandomCharacterMutator.name + "\n" +
                    "                         " + Mutator.FlipRandomBitMutator.name + "\n" +
                    "                         " + Mutator.DuplicateMutator.name + "\n" +
                    "                         " + Mutator.RepeatRandomCharacterMutator.name + "\n" +
                    "                         " + Mutator.ReplaceRandomCharacterMutator.name + "\n" +
                    "                         " + Mutator.SwitchCaseMutator.name + "\n" +
                    "                         " + Mutator.ReverseMutator.name + "\n" +
                    "                       A mutator must be specified in the Format <mutator>:<share>, separated by ','\n" +
                    "                       Example: -m \"insert_ascii:10,insert_letter_or_digit:10\"\n";

    private static final String MSG_MISSING_COMMAND = "Missing command to fuzz.\n\n" + MSG_USAGE;
    private static final String MSG_UNKNOWN_OPTION = "Unknown option: %s\n\n" + MSG_USAGE;
    private static final String MSG_UNKNOWN_COMMAND =  "Could not find command \" %s\n\".";
    private static final String MSG_RUNNING_COMMAND = "Running command: %s\n\n";
    private static final String MSG_UNKNOWN_MUTATOR = "Unknown mutator: %s\n\n" + MSG_USAGE;


    public static void main(String[] args) {
        parseArgs(args);

        if (mutators.isEmpty()) {
            // All mutators with default share
            mutators = List.of(
                    new Mutator.InsertRandomAsciiSymbolMutator(10),
                    new Mutator.InsertRandomLetterOrDigitMutator(5),
                    new Mutator.InsertRandomStringMutator(5),
                    new Mutator.DeleteRandomCharacterMutator(10),
                    new Mutator.FlipRandomBitMutator(10),
                    new Mutator.DuplicateMutator(5),
                    new Mutator.RepeatRandomCharacterMutator(5),
                    new Mutator.ReplaceRandomCharacterMutator(10),
                    new Mutator.SwitchCaseMutator(10),
                    new Mutator.ReverseMutator(1)
            );
        }

        String workingDirectory = "./";

        if (!Files.exists(Paths.get(workingDirectory, commandToFuzz))) {
            throw new RuntimeException(MSG_UNKNOWN_COMMAND.formatted(commandToFuzz));
        }

        ProcessBuilder builder = getProcessBuilderForCommand(commandToFuzz, workingDirectory);
        System.out.printf(MSG_RUNNING_COMMAND, builder.command());


        // Calculate the threshold for each mutator
        mutators.getFirst().threshold = mutators.getFirst().proportion;

        for (int i = 1; i < mutators.size(); i++) {
            mutators.get(i).threshold = mutators.get(i).proportion + mutators.get(i - 1).threshold;
        }


        List<String> mutatedInputs = Collections.nCopies(count, seedInput);

        for (int i = 0; i < passes; i++) {
            mutatedInputs = getMutatedInputs(mutatedInputs, mutators);
            mutatedInputs.replaceAll(input -> input.replace("\n", ""));
            mutatedInputs = runCommand(builder, mutatedInputs);
        }

        System.exit(0);
    }

    private static void parseArgs(String[] args) {
        if (args.length < 1) {
            System.err.println(MSG_USAGE);
            System.exit(1);
        }

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-s":
                    if (i + 1 >= args.length) {
                        System.err.println("Missing seed input.");
                        System.exit(1);
                    }
                    seedInput = args[++i];
                    break;
                case "-n":
                    if (i + 1 >= args.length) {
                        System.err.println("Missing count.");
                        System.exit(1);
                    }
                    count = Integer.parseInt(args[++i]);
                    break;
                case "-p":
                    if (i + 1 >= args.length) {
                        System.err.println("Missing passes.");
                        System.exit(1);
                    }
                    passes = Integer.parseInt(args[++i]);
                    break;
                case "-f":
                    stopOnFirstFailure = false;
                    break;
                case "-m":
                    if (i + 1 >= args.length) {
                        System.err.println("Missing mutators.");
                        System.exit(1);
                    }
                    args[i + 1] = args[i + 1].replace(" ", "");
                    String[] mutators = args[++i].split(",");
                    for (String mutator : mutators) {
                        String[] parts = mutator.split(":");
                        if (parts.length != 2) {
                            System.err.printf(MSG_UNKNOWN_MUTATOR, mutator);
                            System.exit(1);
                        }
                        String name = parts[0];
                        int proportion = Integer.parseInt(parts[1]);

                        switch (name) {
                            case Mutator.InsertRandomAsciiSymbolMutator.name:
                                Fuzzer.mutators.add(new Mutator.InsertRandomAsciiSymbolMutator(proportion));
                                break;
                            case Mutator.InsertRandomLetterOrDigitMutator.name:
                                Fuzzer.mutators.add(new Mutator.InsertRandomLetterOrDigitMutator(proportion));
                                break;
                            case Mutator.InsertRandomStringMutator.name:
                                Fuzzer.mutators.add(new Mutator.InsertRandomStringMutator(proportion));
                                break;
                            case Mutator.DeleteRandomCharacterMutator.name:
                                Fuzzer.mutators.add(new Mutator.DeleteRandomCharacterMutator(proportion));
                                break;
                            case Mutator.FlipRandomBitMutator.name:
                                Fuzzer.mutators.add(new Mutator.FlipRandomBitMutator(proportion));
                                break;
                            case Mutator.DuplicateMutator.name:
                                Fuzzer.mutators.add(new Mutator.DuplicateMutator(proportion));
                                break;
                            case Mutator.RepeatRandomCharacterMutator.name:
                                Fuzzer.mutators.add(new Mutator.RepeatRandomCharacterMutator(proportion));
                                break;
                            case Mutator.ReplaceRandomCharacterMutator.name:
                                Fuzzer.mutators.add(new Mutator.ReplaceRandomCharacterMutator(proportion));
                                break;
                            case Mutator.SwitchCaseMutator.name:
                                Fuzzer.mutators.add(new Mutator.SwitchCaseMutator(proportion));
                                break;
                            case Mutator.ReverseMutator.name:
                                Fuzzer.mutators.add(new Mutator.ReverseMutator(proportion));
                                break;
                            default:
                                System.err.printf(MSG_UNKNOWN_MUTATOR, name);
                                System.exit(1);
                        }
                    }
                    break;
                default:
                    if (commandToFuzz == null) {
                        commandToFuzz = args[i];
                    } else {
                        System.err.printf(MSG_UNKNOWN_OPTION, args[i]);
                        System.exit(1);
                    }
            }
        }

        if (commandToFuzz == null) {
            System.err.println(MSG_MISSING_COMMAND);
            System.exit(1);
        }
    }

    private static ProcessBuilder getProcessBuilderForCommand(String command, String workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder();
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            builder.command("cmd.exe", "/c", "\"\"" + command + "\"\"");
        } else {
            builder.command("sh", "-c", "\"\"" + command + "\"\"");
        }
        builder.directory(new File(workingDirectory));
        builder.redirectErrorStream(true); // redirect stderr to stdout
        return builder;
    }

    private static List<String> runCommand(ProcessBuilder builder, List<String> mutatedInputs) {
        List<String> re = new ArrayList<>(mutatedInputs);

        for (String mutatedInput : mutatedInputs) {
            attempt++;

            try {
                Process process = builder.start();

                OutputStream stdin = process.getOutputStream();
                InputStream stdout = process.getInputStream();

                stdin.write(mutatedInput.getBytes());
                stdin.write(System.lineSeparator().getBytes());
                stdin.flush();

                stdin.close();

                String output = readStreamIntoString(stdout);

                stdout.close();

                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    System.out.println("Found failure on attempt " + attempt);
                    System.out.println("Input: " + mutatedInput);
                    System.out.println("Command failed with exit code " + exitCode);
                    System.out.println();
                    System.out.println(("Output: " + output).trim());
                    System.out.println();
                    System.out.println();

                    if (stopOnFirstFailure) {
                        System.exit(2);
                    }

                    re.remove(mutatedInput);
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Error while trying input: " + mutatedInput);
                System.err.println(e.getMessage());
            }
        }

        return re;
    }

    private static String readStreamIntoString(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        return reader.lines()
                .map(line -> line + System.lineSeparator())
                .collect(Collectors.joining());
    }

    private static List<String> getMutatedInputs(List<String> seedInputs, List<Mutator> mutators) {
        List<String> re = new ArrayList<>();

        for (String seedInput : seedInputs) {
            // Select random mutator
            int selection = random.nextInt(mutators.getLast().threshold);

            Mutator m = null;
            for (Mutator mutator : mutators) {
                if (selection < mutator.threshold) {
                    m = mutator;
                    break;
                }
            }

            re.add(m.mutate(seedInput));
        }

        return re;
    }


    abstract static class Mutator {
        int proportion;
        int threshold;

        Mutator(int proportion) {
            this.proportion = proportion;
        }

        abstract String mutate(String in);


        static class InsertRandomAsciiSymbolMutator extends Mutator {
            static final String name = "insert_ascii_symbol";

            InsertRandomAsciiSymbolMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                char insertion = (char) ('\0' + random.nextInt(128));
                int offset = random.nextInt(in.length());
                return new StringBuilder(in).insert(offset, insertion).toString();
            }
        }

        static class InsertRandomLetterOrDigitMutator extends Mutator {
            static final String name = "insert_letter_or_digit";

            InsertRandomLetterOrDigitMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                String alphabet = "abcdefghijklmnopqrstuvwxyz" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "0123456789";
                char insertion = alphabet.charAt(random.nextInt(alphabet.length()));
                int offset = random.nextInt(in.length());
                return new StringBuilder(in).insert(offset, insertion).toString();
            }
        }

        static class InsertRandomStringMutator extends Mutator {
            static final String name = "insert_string";

            InsertRandomStringMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                String alphabet = "abcdefghijklmnopqrstuvwxyz" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "0123456789";
                int length = random.nextInt(50);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < length; i++) {
                    sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                int offset = random.nextInt(in.length());
                return new StringBuilder(in).insert(offset, sb).toString();
            }
        }

        static class DeleteRandomCharacterMutator extends Mutator {
            static final String name = "delete_char";

            DeleteRandomCharacterMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                int position = random.nextInt(in.length());
                return new StringBuilder(in).deleteCharAt(position).toString();
            }
        }

        static class FlipRandomBitMutator extends Mutator {
            static final String name = "flip_bit";

            FlipRandomBitMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                int charPosition = random.nextInt(in.length());
                int bitPosition = random.nextInt(8);
                char c = in.charAt(charPosition);
                char flipped = (char) (c ^ (1 << bitPosition));
                return new StringBuilder(in).replace(charPosition, charPosition + 1, String.valueOf(flipped)).toString();
            }
        }

        static class DuplicateMutator extends Mutator {
            static final String name = "duplicate_char";

            DuplicateMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                return in + in;
            }
        }

        static class RepeatRandomCharacterMutator extends Mutator {
            static final String name = "repeat_char";

            RepeatRandomCharacterMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                int position = random.nextInt(in.length());
                return new StringBuilder(in).insert(position + 1, in.charAt(position)).toString();
            }
        }

        static class ReplaceRandomCharacterMutator extends Mutator {
            static final String name = "replace_char";

            ReplaceRandomCharacterMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                char insertion = (char) ('\0' + random.nextInt(128));
                int position = random.nextInt(in.length());
                return new StringBuilder(in).replace(position, position + 1, String.valueOf(insertion)).toString();
            }
        }

        static class SwitchCaseMutator extends Mutator {
            static final String name = "switch_case";

            SwitchCaseMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                int position = random.nextInt(in.length());
                char c = in.charAt(position);

                if (Character.isUpperCase(c))
                    c = Character.toLowerCase(c);
                else
                    c = Character.toUpperCase(c);

                return new StringBuilder(in).replace(position, position + 1, String.valueOf(c)).toString();
            }
        }

        static class ReverseMutator extends Mutator {
            static final String name = "reverse_all";

            ReverseMutator(int proportion) {
                super(proportion);
            }

            @Override
            public String mutate(String in) {
                return new StringBuilder(in).reverse().toString();
            }
        }
    }
}
