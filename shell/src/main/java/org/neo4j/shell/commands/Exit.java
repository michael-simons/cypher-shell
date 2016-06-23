package org.neo4j.shell.commands;

import org.neo4j.shell.Command;
import org.neo4j.shell.CypherShell;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

/**
 * Command to exit the shell. Equivalent to hitting Ctrl-D.
 */
public class Exit implements Command {
    public static final String COMMAND_NAME = ":exit";
    private final CypherShell shell;

    public Exit(@Nonnull final CypherShell shell) {
        this.shell = shell;
    }

    @Nonnull
    @Override
    public String getName() {
        return COMMAND_NAME;
    }

    @Nonnull
    @Override
    public String getDescription() {
        return "Exit the shell";
    }

    @Nonnull
    @Override
    public String getUsage() {
        return "";
    }

    @Nonnull
    @Override
    public String getHelp() {
        return "Exit the shell. Corresponds to entering @|bold CTRL-D|@.";
    }

    @Nonnull
    @Override
    public List<String> getAliases() {
        return Arrays.asList(":quit");
    }

    @Override
    public void execute(@Nonnull List<String> args) throws ExitException {
        shell.printOut("Exiting. Bye bye.");

        throw new ExitException(0);
    }

    public class ExitException extends Error {
        private final int code;

        public ExitException(int code) {
            super();
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}