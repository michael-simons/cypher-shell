package org.neo4j.shell.cli;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.action.StoreConstArgumentAction;
import net.sourceforge.argparse4j.impl.action.StoreTrueArgumentAction;
import net.sourceforge.argparse4j.impl.choice.CollectionArgumentChoice;
import net.sourceforge.argparse4j.impl.type.BooleanArgumentType;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.ArgumentGroup;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.ArgumentType;
import net.sourceforge.argparse4j.inf.MutuallyExclusiveGroup;
import net.sourceforge.argparse4j.inf.Namespace;

import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.neo4j.shell.ConnectionConfig;
import org.neo4j.shell.ParameterMap;

import static java.lang.String.format;
import static org.neo4j.shell.cli.FailBehavior.FAIL_AT_END;
import static org.neo4j.shell.cli.FailBehavior.FAIL_FAST;

/**
 * Command line argument parsing and related stuff
 */
public class CliArgHelper {

    static final Pattern ADDRESS_ARG_PATTERN =
            Pattern.compile("\\s*(?<scheme>[a-zA-Z0-9+\\-.]+://)?((?<username>\\w+):(?<password>[^\\s]+)@)?(?<host>[a-zA-Z\\d\\-.]+)?(:(?<port>\\d+))?\\s*");

    /**
     * @param args to parse
     * @return null in case of error, commandline arguments otherwise
     */
    @Nullable
    public static CliArgs parse(@Nonnull String... args) {
        final CliArgs cliArgs = new CliArgs();

        final ArgumentParser parser = setupParser(cliArgs.getParameters());
        final Namespace ns;

        try {
            ns = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            return null;
        }
        return getCliArgs( cliArgs, parser, ns );
    }

    /**
     * @param args to parse
     * @return commandline arguments
     * @throws ArgumentParserException if an argument can't be parsed.
     */
    public static CliArgs parseAndThrow( @Nonnull String... args ) throws ArgumentParserException
    {
        final CliArgs cliArgs = new CliArgs();
        final ArgumentParser parser = setupParser( cliArgs.getParameters() );
        final Namespace ns = parser.parseArgs( args );
        return getCliArgs( cliArgs, parser, ns );
    }

    private static CliArgs getCliArgs( CliArgs cliArgs, ArgumentParser parser, Namespace ns )
    {
        // Parse address string, returns null on error
        final Matcher addressMatcher = parseAddressMatcher( parser, ns.getString( "address"));

        if (addressMatcher == null) {
            return null;
        }

        //---------------------
        // Connection arguments
        cliArgs.setScheme(addressMatcher.group("scheme"), "bolt://");
        cliArgs.setHost(addressMatcher.group("host"), "localhost");
        // Safe, regex only matches integers
        String portString = addressMatcher.group("port");
        cliArgs.setPort(portString == null ? 7687 : Integer.parseInt(portString));
        // Also parse username and password from address if available
        cliArgs.setUsername(addressMatcher.group("username"), "");
        cliArgs.setPassword(addressMatcher.group("password"), "");

        // Only overwrite user/pass from address string if the arguments were specified
        String user = ns.getString("username");
        if (!user.isEmpty()) {
            cliArgs.setUsername(user, cliArgs.getUsername());
        }
        String pass = ns.getString("password");
        if (!pass.isEmpty()) {
            cliArgs.setPassword(pass, cliArgs.getPassword());
        }
        cliArgs.setEncryption(ns.getBoolean("encryption"));
        cliArgs.setDatabase(ns.getString("database"));
        cliArgs.setInputFilename(ns.getString( "file" )  );

        //----------------
        // Other arguments
        // cypher string might not be given, represented by null
        cliArgs.setCypher(ns.getString("cypher"));
        // Fail behavior as sensible default and returns a proper type
        cliArgs.setFailBehavior(ns.get("fail-behavior"));

        //Set Output format
        cliArgs.setFormat(Format.parse(ns.get("format")));

        cliArgs.setDebugMode(ns.getBoolean("debug"));

        cliArgs.setNonInteractive(ns.getBoolean("force-non-interactive"));

        cliArgs.setWrap(ns.getBoolean("wrap"));

        cliArgs.setNumSampleRows(ns.getInt("sample-rows"));

        cliArgs.setVersion(ns.getBoolean("version"));

        cliArgs.setDriverVersion(ns.getBoolean("driver-version"));

        return cliArgs;
    }

    @Nullable
    private static Matcher parseAddressMatcher(ArgumentParser parser, String address) {
        Matcher matcher = ADDRESS_ARG_PATTERN.matcher(address);
        if (!matcher.matches()) {
            // Match behavior in built-in error handling
            PrintWriter printWriter = new PrintWriter(System.err);
            parser.printUsage(printWriter);
            printWriter.println("cypher-shell: error: Failed to parse address: '" + address + "'");
            printWriter.println("\n  Address should be of the form: [scheme://][username:password@][host][:port]");
            printWriter.flush();
            return null;
        }
        return matcher;
    }

    private static ArgumentParser setupParser(ParameterMap parameterMap)
    {
        ArgumentParser parser = ArgumentParsers.newArgumentParser( "cypher-shell" ).defaultHelp( true ).description(
                format( "A command line shell where you can execute Cypher against an instance of Neo4j. " +
                        "By default the shell is interactive but you can use it for scripting by passing cypher " +
                        "directly on the command line or by piping a file with cypher statements (requires Powershell on Windows)." +
                        "%n%n" +
                        "example of piping a file:%n" +
                        "  cat some-cypher.txt | cypher-shell" ) );

        ArgumentGroup connGroup = parser.addArgumentGroup("connection arguments");
        connGroup.addArgument("-a", "--address")
                .help("address and port to connect to")
                .setDefault("neo4j://localhost:7687");
        connGroup.addArgument("-u", "--username")
                .setDefault("")
                .help("username to connect as. Can also be specified using environment variable " + ConnectionConfig.USERNAME_ENV_VAR);
        connGroup.addArgument("-p", "--password")
                .setDefault("")
                .help("password to connect with. Can also be specified using environment variable " + ConnectionConfig.PASSWORD_ENV_VAR);
        connGroup.addArgument("--encryption")
                .help("whether the connection to Neo4j should be encrypted; must be consistent with Neo4j's " +
                        "configuration")
                .type(new BooleanArgumentType())
                .setDefault(false);
        connGroup.addArgument("-d", "--database")
                .help("database to connect to. Can also be specified using environment variable " + ConnectionConfig.DATABASE_ENV_VAR)
                .setDefault("");
        connGroup.addArgument("-f", "--file")
                .help("Pass a file with cypher statements to be executed. After the statements have been executed cypher-shell will be shutdown");

        MutuallyExclusiveGroup failGroup = parser.addMutuallyExclusiveGroup();
        failGroup.addArgument("--fail-fast")
                .help("exit and report failure on first error when reading from file (this is the default behavior)")
                .dest("fail-behavior")
                .setConst(FAIL_FAST)
                .action(new StoreConstArgumentAction());
        failGroup.addArgument("--fail-at-end")
                .help("exit and report failures at end of input when reading from file")
                .dest("fail-behavior")
                .setConst(FAIL_AT_END)
                .action(new StoreConstArgumentAction());
        parser.setDefault("fail-behavior", FAIL_FAST);

        parser.addArgument("--format")
                .help("desired output format, verbose displays results in tabular format and prints statistics, " +
                        "plain displays data with minimal formatting")
                .choices(new CollectionArgumentChoice<>(
                        Format.AUTO.name().toLowerCase(),
                        Format.VERBOSE.name().toLowerCase(),
                        Format.PLAIN.name().toLowerCase()))
                .setDefault(Format.AUTO.name().toLowerCase());

        parser.addArgument( "-P", "--param" )
              .help( "Add a parameter to this session. Example: `-P \"number => 3\"`. This argument can be specified multiple times." )
              .action( new AddParamArgumentAction( parameterMap ) );

        parser.addArgument("--debug")
                .help("print additional debug information")
                .action(new StoreTrueArgumentAction());

        parser.addArgument("--non-interactive")
                .help("force non-interactive mode, only useful if auto-detection fails (like on Windows)")
                .dest("force-non-interactive")
              .action(new StoreTrueArgumentAction());

        parser.addArgument("--sample-rows")
                .help("number of rows sampled to compute table widths (only for format=VERBOSE)")
                .type(new PositiveIntegerType())
                .dest("sample-rows")
                .setDefault(CliArgs.DEFAULT_NUM_SAMPLE_ROWS);

        parser.addArgument("--wrap")
                .help("wrap table colum values if column is too narrow (only for format=VERBOSE)")
                .type(new BooleanArgumentType())
                .setDefault(true);

        parser.addArgument("-v", "--version")
                .help("print version of cypher-shell and exit")
                .action(new StoreTrueArgumentAction());

        parser.addArgument("--driver-version")
                .help("print version of the Neo4j Driver used and exit")
                .dest("driver-version")
                .action(new StoreTrueArgumentAction());

        parser.addArgument("cypher")
                .nargs("?")
                .help("an optional string of cypher to execute and then exit");

        return parser;
    }

    private static class PositiveIntegerType implements ArgumentType<Integer> {
        @Override
        public Integer convert(ArgumentParser parser, Argument arg, String value) throws ArgumentParserException {
            try {
                int result = Integer.parseInt(value);
                if (result < 1) throw new NumberFormatException(value);
                return result;
            } catch (NumberFormatException nfe) {
                throw new ArgumentParserException("Invalid value: "+value, parser);
            }
        }
    }
}
