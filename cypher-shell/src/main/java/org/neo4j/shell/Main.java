package org.neo4j.shell;

import jline.console.ConsoleReader;

import java.io.InputStream;
import java.io.PrintStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.shell.build.Build;
import org.neo4j.shell.cli.CliArgHelper;
import org.neo4j.shell.cli.CliArgs;
import org.neo4j.shell.commands.CommandHelper;
import org.neo4j.shell.exception.CommandException;
import org.neo4j.shell.log.AnsiLogger;
import org.neo4j.shell.log.Logger;
import org.neo4j.shell.prettyprint.PrettyConfig;

import java.io.OutputStream;

import static org.neo4j.shell.ShellRunner.isInputInteractive;
import static org.neo4j.shell.ShellRunner.isOutputInteractive;

public class Main {
    static final String NEO_CLIENT_ERROR_SECURITY_UNAUTHORIZED = "Neo.ClientError.Security.Unauthorized";
    private final InputStream in;
    private final PrintStream out;

    public static void main(String[] args) {
        CliArgs cliArgs = CliArgHelper.parse(args);

        // if null, then command line parsing went wrong
        // CliArgs has already printed errors.
        if (cliArgs == null) {
            System.exit(1);
        }

        Main main = new Main();
        main.startShell(cliArgs);
    }

    private Main() {
        this(System.in, System.out);
    }

    /**
     * For testing purposes
     */
    Main(final InputStream in, final PrintStream out) {
        this.in = in;
        this.out = out;
    }

    void startShell(@Nonnull CliArgs cliArgs) {
        if (cliArgs.getVersion()) {
            out.println("Cypher-Shell " + Build.version());
        }
        if (cliArgs.getDriverVersion()) {
            out.println("Neo4j Driver " + Build.driverVersion());
        }
        if (cliArgs.getVersion() || cliArgs.getDriverVersion()) {
            return;
        }
        Logger logger = new AnsiLogger(cliArgs.getDebugMode());
        PrettyConfig prettyConfig = new PrettyConfig(cliArgs);
        ConnectionConfig connectionConfig = new ConnectionConfig(
                cliArgs.getScheme(),
                cliArgs.getHost(),
                cliArgs.getPort(),
                cliArgs.getUsername(),
                cliArgs.getPassword(),
                cliArgs.getEncryption(),
                cliArgs.getDatabase());

        try {
            CypherShell shell = new CypherShell(logger, prettyConfig, ShellRunner.shouldBeInteractive( cliArgs ), cliArgs.getParameters());
            // Can only prompt for password if input has not been redirected
            connectMaybeInteractively(shell, connectionConfig, isInputInteractive(), isOutputInteractive());

            // Construct shellrunner after connecting, due to interrupt handling
            ShellRunner shellRunner = ShellRunner.getShellRunner(cliArgs, shell, logger, connectionConfig);

            CommandHelper commandHelper = new CommandHelper(logger, shellRunner.getHistorian(), shell);

            shell.setCommandHelper(commandHelper);

            int code = shellRunner.runUntilEnd();
            System.exit(code);
        } catch (Throwable e) {
            logger.printError(e);
            System.exit(1);
        }
    }

    /**
     * Connect the shell to the server, and try to handle missing passwords and such
     */
    void connectMaybeInteractively(@Nonnull CypherShell shell,
                                   @Nonnull ConnectionConfig connectionConfig,
                                   boolean inputInteractive,
                                   boolean outputInteractive)
            throws Exception {

        OutputStream outputStream = outputInteractive ? out : new ThrowawayOutputStream();

        ConsoleReader consoleReader = new ConsoleReader(in, outputStream);
        // Disable expansion of bangs: !
        consoleReader.setExpandEvents(false);
        // Ensure Reader does not handle user input for ctrl+C behaviour
        consoleReader.setHandleUserInterrupt(false);

        try {
            shell.connect(connectionConfig);
        } catch (AuthenticationException e) {
            // Only prompt for username/password if they weren't used
            if (!connectionConfig.username().isEmpty() && !connectionConfig.password().isEmpty()) {
                throw e;
            }
            // else need to prompt for username and password
            if (inputInteractive) {
                if (connectionConfig.username().isEmpty()) {
                    String username = outputInteractive ?
                                      promptForNonEmptyText("username", consoleReader, null) :
                                      promptForText("username", consoleReader, null);
                    connectionConfig.setUsername(username);
                }
                if (connectionConfig.password().isEmpty()) {
                    connectionConfig.setPassword(promptForText("password", consoleReader, '*'));
                }
                // try again
                shell.connect(connectionConfig);
            } else {
                // Can't prompt because input has been redirected
                throw e;
            }
        } finally {
            consoleReader.close();
        }
    }

    /**
     * @param prompt
     *         to display to the user
     * @param mask
     *         single character to display instead of what the user is typing, use null if text is not secret
     * @return the text which was entered
     * @throws Exception
     *         in case of errors
     */
    @Nonnull
    private String promptForNonEmptyText(@Nonnull String prompt, @Nonnull ConsoleReader consoleReader, @Nullable Character mask) throws Exception {
        String text = promptForText(prompt, consoleReader, mask);
        if (!text.isEmpty()) {
            return text;
        }
        consoleReader.println( prompt + " cannot be empty" );
        consoleReader.println();
        return promptForNonEmptyText(prompt, consoleReader, mask);
    }

    /**
     * @param prompt
     *         to display to the user
     * @param mask
     *         single character to display instead of what the user is typing, use null if text is not secret
     * @param consoleReader
     *         the reader
     * @return the text which was entered
     * @throws Exception
     *         in case of errors
     */
    @Nonnull
    private String promptForText(@Nonnull String prompt, @Nonnull ConsoleReader consoleReader, @Nullable Character mask) throws Exception {
        String line = consoleReader.readLine(prompt + ": ", mask);
        if (line == null) {
            throw new CommandException("No text could be read, exiting...");
        }

        return line;
    }

    private static class ThrowawayOutputStream extends OutputStream {
        @Override
        public void write( int b )
        {
        }
    }
}
