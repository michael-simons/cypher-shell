package org.neo4j.shell.cli;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.shell.ConnectionConfig;
import org.neo4j.shell.CypherShell;
import org.neo4j.shell.DatabaseManager;
import org.neo4j.shell.Historian;
import org.neo4j.shell.ShellParameterMap;
import org.neo4j.shell.StatementExecuter;
import org.neo4j.shell.TransactionHandler;
import org.neo4j.shell.UserMessagesHandler;
import org.neo4j.shell.exception.CommandException;
import org.neo4j.shell.exception.ExitException;
import org.neo4j.shell.exception.NoMoreInputException;
import org.neo4j.shell.log.AnsiFormattedText;
import org.neo4j.shell.log.Logger;
import org.neo4j.shell.parser.ShellStatementParser;
import org.neo4j.shell.parser.StatementParser;
import org.neo4j.shell.prettyprint.OutputFormatter;
import org.neo4j.shell.prettyprint.PrettyPrinter;
import org.neo4j.shell.state.BoltStateHandler;
import sun.misc.Signal;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class InteractiveShellRunnerTest {
    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Logger logger;
    private StatementExecuter cmdExecuter;
    private File historyFile;
    private StatementParser statementParser;
    private TransactionHandler txHandler;
    private DatabaseManager databaseManager;
    private ClientException badLineError;
    private UserMessagesHandler userMessagesHandler;
    private ConnectionConfig connectionConfig;

    @Before
    public void setup() throws Exception {
        statementParser = new ShellStatementParser();
        logger = mock(Logger.class);
        cmdExecuter = mock(StatementExecuter.class);
        txHandler = mock(TransactionHandler.class);
        databaseManager = mock(DatabaseManager.class);
        connectionConfig = mock(ConnectionConfig.class);
        historyFile = temp.newFile();
        badLineError = new ClientException("Found a bad line");
        userMessagesHandler = mock(UserMessagesHandler.class);
        when(databaseManager.getActualDatabaseAsReportedByServer()).thenReturn("mydb");
        when(userMessagesHandler.getWelcomeMessage()).thenReturn("Welcome to cypher-shell!");
        when(userMessagesHandler.getExitMessage()).thenReturn("Exit message");
        when(connectionConfig.username()).thenReturn("myusername");

        doThrow(badLineError).when(cmdExecuter).execute(contains("bad"));
        doReturn(System.out).when(logger).getOutputStream();
    }

    @Test
    public void testSimple() throws Exception {
        String input = "good1;\n" +
                "good2;\n";
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, statementParser,
                new ByteArrayInputStream(input.getBytes()), historyFile, userMessagesHandler, connectionConfig);
        runner.runUntilEnd();

        verify(cmdExecuter).execute("good1;");
        verify(cmdExecuter).execute("\ngood2;");
        verify(cmdExecuter, times(3)).lastNeo4jErrorCode();
        verifyNoMoreInteractions(cmdExecuter);
    }

    @Test
    public void runUntilEndShouldKeepGoingOnErrors() throws IOException, CommandException {
        String input = "good1;\n" +
                "bad1;\n" +
                "good2;\n" +
                "bad2;\n" +
                "good3;\n";
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, statementParser,
                new ByteArrayInputStream(input.getBytes()), historyFile, userMessagesHandler, connectionConfig);

        int code = runner.runUntilEnd();

        assertEquals("Wrong exit code", 0, code);

        verify(cmdExecuter).execute("good1;");
        verify(cmdExecuter).execute("\nbad1;");
        verify(cmdExecuter).execute("\ngood2;");
        verify(cmdExecuter).execute("\nbad2;");
        verify(cmdExecuter).execute("\ngood3;");
        verify(cmdExecuter, times(6)).lastNeo4jErrorCode();
        verifyNoMoreInteractions(cmdExecuter);

        verify(logger, times(2)).printError(badLineError);
    }

    @Test
    public void runUntilEndShouldStopOnExitExceptionAndReturnCode() throws IOException, CommandException {
        String input = "good1;\n" +
                "bad1;\n" +
                "good2;\n" +
                "exit;\n" +
                "bad2;\n" +
                "good3;\n";
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, statementParser,
                new ByteArrayInputStream(input.getBytes()), historyFile, userMessagesHandler, connectionConfig);

        doThrow(new ExitException(1234)).when(cmdExecuter).execute(contains("exit;"));

        int code = runner.runUntilEnd();

        assertEquals("Wrong exit code", 1234, code);

        verify(cmdExecuter).execute("good1;");
        verify(cmdExecuter).execute("\nbad1;");
        verify(cmdExecuter).execute("\ngood2;");
        verify(cmdExecuter).execute("\nexit;");
        verify(cmdExecuter, times(4)).lastNeo4jErrorCode();
        verifyNoMoreInteractions(cmdExecuter);

        verify(logger).printError(badLineError);
    }

    @Test
    public void historyIsRecorded() throws Exception {
        // given

        String cmd1 = ":set var \"3\"";
        String cmd2 = ":help exit";
        String input = cmd1 + "\n" + cmd2 + "\n";

        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, statementParser,
                new ByteArrayInputStream(input.getBytes()), historyFile, userMessagesHandler, connectionConfig);

        // when
        runner.runUntilEnd();

        // then
        Historian historian = runner.getHistorian();
        historian.flushHistory();

        List<String> history = Files.readAllLines(historyFile.toPath());

        assertEquals(2, history.size());
        assertEquals(cmd1, history.get(0));
        assertEquals(cmd2, history.get(1));

        history = historian.getHistory();
        assertEquals(2, history.size());
        assertEquals(cmd1, history.get(0));
        assertEquals(cmd2, history.get(1));
    }

    @Test
    public void unescapedBangWorks() throws Exception {
        // given
        PrintStream mockedErr = mock(PrintStream.class);
        when(logger.getErrorStream()).thenReturn(mockedErr);

        // Bangs need escaping in JLine by default, just like in bash, but we have disabled that
        InputStream inputStream = new ByteArrayInputStream(":set var \"String with !bang\"\n".getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, statementParser, inputStream, historyFile,
                userMessagesHandler, connectionConfig);

        // when
        List<String> statements = runner.readUntilStatement();
        // then
        assertEquals(":set var \"String with !bang\"\n", statements.get(0));
    }

    @Test
    public void escapedBangWorks() throws Exception {
        // given
        PrintStream mockedErr = mock(PrintStream.class);
        when(logger.getErrorStream()).thenReturn(mockedErr);

        // Bangs need escaping in JLine by default, just like in bash, but we have disabled that
        InputStream inputStream = new ByteArrayInputStream(":set var \"String with \\!bang\"\n".getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, statementParser, inputStream, historyFile,
                userMessagesHandler, connectionConfig);

        // when
        List<String> statements = runner.readUntilStatement();
        // then
        assertEquals(":set var \"String with \\!bang\"\n", statements.get(0));
    }

    @Test
    public void justNewLineThrowsNoMoreInput() throws Exception {
        // then
        thrown.expect(NoMoreInputException.class);

        // given
        String inputString = "\n";
        InputStream inputStream = new ByteArrayInputStream(inputString.getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, new ShellStatementParser(), inputStream,
                historyFile, userMessagesHandler, connectionConfig);

        // when
        runner.readUntilStatement();
    }

    @Test
    public void emptyStringThrowsNoMoreInput() throws Exception {
        // then
        thrown.expect(NoMoreInputException.class);

        // given
        String inputString = "";
        InputStream inputStream = new ByteArrayInputStream(inputString.getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, new ShellStatementParser(), inputStream,
                historyFile, userMessagesHandler, connectionConfig);

        // when
        runner.readUntilStatement();
    }

    @Test
    public void emptyLineIsIgnored() throws Exception {
        // given
        String inputString = "     \nCREATE (n:Person) RETURN n;\n";
        InputStream inputStream = new ByteArrayInputStream(inputString.getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, new ShellStatementParser(), inputStream,
                historyFile, userMessagesHandler, connectionConfig);

        // when
        List<String> statements = runner.readUntilStatement();

        // then
        assertEquals(1, statements.size());
        assertThat(statements.get(0), is("CREATE (n:Person) RETURN n;"));
    }

    @Test
    public void testPrompt() throws Exception {
        // given
        InputStream inputStream = new ByteArrayInputStream("".getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, statementParser, inputStream,
                historyFile, userMessagesHandler, connectionConfig);

        // when
        when(txHandler.isTransactionOpen()).thenReturn(false);
        AnsiFormattedText prompt = runner.updateAndGetPrompt();

        // then
        String wantedPrompt = "myusername@mydb> ";
        assertEquals(wantedPrompt, prompt.plainString());

        // when
        statementParser.parseMoreText("  \t \n   "); // whitespace
        prompt = runner.updateAndGetPrompt();

        // then
        assertEquals(wantedPrompt, prompt.plainString());

        // when
        statementParser.parseMoreText("bla bla"); // non whitespace
        prompt = runner.updateAndGetPrompt();

        // then
        assertEquals(OutputFormatter.repeat(' ', wantedPrompt.length()), prompt.plainString());
    }

    @Test
    public void testLongPrompt() throws Exception {
        // given
        InputStream inputStream = new ByteArrayInputStream("".getBytes());
        String actualDbName = "TheLongestDbNameEverCreatedInAllOfHistoryAndTheUniversePlusSome";
        when(databaseManager.getActualDatabaseAsReportedByServer()).thenReturn(actualDbName);
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, statementParser, inputStream,
                historyFile, userMessagesHandler, connectionConfig);

        // when
        when(txHandler.isTransactionOpen()).thenReturn(false);
        AnsiFormattedText prompt = runner.updateAndGetPrompt();

        // then
        String wantedPrompt = format("myusername@%s%n> ", actualDbName);
        assertEquals(wantedPrompt, prompt.plainString());

        // when
        statementParser.parseMoreText("  \t \n   "); // whitespace
        prompt = runner.updateAndGetPrompt();

        // then
        assertEquals(wantedPrompt, prompt.plainString());

        // when
        statementParser.parseMoreText("bla bla"); // non whitespace
        prompt = runner.updateAndGetPrompt();

        // then
        assertEquals("", prompt.plainString());
    }

    @Test
    public void testPromptInTx() throws Exception {
        // given
        InputStream inputStream = new ByteArrayInputStream("".getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, statementParser, inputStream, historyFile,
                userMessagesHandler, connectionConfig);

        // when
        when(txHandler.isTransactionOpen()).thenReturn(true);
        AnsiFormattedText prompt = runner.updateAndGetPrompt();

        // then
        String wantedPrompt = "myusername@mydb# ";
        assertEquals(wantedPrompt, prompt.plainString());

        // when
        statementParser.parseMoreText("  \t \n   "); // whitespace
        prompt = runner.updateAndGetPrompt();

        // then
        assertEquals(wantedPrompt, prompt.plainString());

        // when
        statementParser.parseMoreText("bla bla"); // non whitespace
        prompt = runner.updateAndGetPrompt();

        // then
        assertEquals(OutputFormatter.repeat(' ', wantedPrompt.length()), prompt.plainString());
    }

    @Test
    public void multilineRequiresNewLineOrSemicolonToEnd() throws Exception {
        // given
        String inputString = "  \\   \nCREATE (n:Person) RETURN n\n";
        InputStream inputStream = new ByteArrayInputStream(inputString.getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, new ShellStatementParser(), inputStream,
                historyFile, userMessagesHandler, connectionConfig);

        // when
        runner.runUntilEnd();

        // then
        verify(cmdExecuter).lastNeo4jErrorCode();
        verifyNoMoreInteractions(cmdExecuter);
    }

    @Test
    public void printsWelcomeAndExitMessage() throws Exception {
        // given
        String inputString = "\nCREATE (n:Person) RETURN n\n;\n";
        InputStream inputStream = new ByteArrayInputStream(inputString.getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger,
                new ShellStatementParser(), inputStream, historyFile, userMessagesHandler, connectionConfig);

        // when
        runner.runUntilEnd();

        // then
        verify(logger).printIfVerbose("Welcome to cypher-shell!");
        verify(logger).printIfVerbose("Exit message");
    }

    @Test
    public void multilineEndsOnSemicolonOnNewLine() throws Exception {
        // given
        String inputString = "\nCREATE (n:Person) RETURN n\n;\n";
        InputStream inputStream = new ByteArrayInputStream(inputString.getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, new ShellStatementParser(), inputStream,
                historyFile, userMessagesHandler, connectionConfig);

        // when
        runner.runUntilEnd();

        // then
        verify(cmdExecuter).execute("CREATE (n:Person) RETURN n\n;");
    }

    @Test
    public void multilineEndsOnSemicolonOnSameLine() throws Exception {
        // given
        String inputString = "\nCREATE (n:Person) RETURN n;\n";
        InputStream inputStream = new ByteArrayInputStream(inputString.getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger, new ShellStatementParser(), inputStream,
                historyFile, userMessagesHandler, connectionConfig);

        // when
        runner.runUntilEnd();

        // then
        verify(cmdExecuter).execute("CREATE (n:Person) RETURN n;");
    }

    @Test
    public void testSignalHandleOutsideExecution() throws Exception {
        // given
        InputStream inputStream = new ByteArrayInputStream("".getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(cmdExecuter, txHandler, databaseManager, logger,
                new ShellStatementParser(), inputStream, historyFile, userMessagesHandler, connectionConfig);

        // when
        runner.handle(new Signal(InteractiveShellRunner.INTERRUPT_SIGNAL));

        // then
        verify(cmdExecuter).lastNeo4jErrorCode();
        verifyNoMoreInteractions(cmdExecuter);
        verify(logger).printError("@|RED \nInterrupted (Note that Cypher queries must end with a |@" +
                "@|RED,BOLD semicolon. |@" +
                "@|RED Type |@@|RED,BOLD :exit|@@|RED,BOLD  |@" +
                "@|RED to exit the shell.)|@");
    }

    @Test
    public void testSignalHandleDuringExecution() throws Exception {
        // given
        BoltStateHandler boltStateHandler = mock(BoltStateHandler.class);
        FakeInterruptableShell fakeShell = spy(new FakeInterruptableShell(logger, boltStateHandler));
        InputStream inputStream = new ByteArrayInputStream("RETURN 1;\n".getBytes());
        InteractiveShellRunner runner = new InteractiveShellRunner(fakeShell, fakeShell, fakeShell, logger,
                new ShellStatementParser(), inputStream, historyFile, userMessagesHandler, connectionConfig);

        // during
        Thread t = new Thread(runner::runUntilEnd);
        t.start();

        // wait until execution has begun
        while (!t.getState().equals(Thread.State.TIMED_WAITING)) {
            Thread.sleep(100L);
        }

        // when
        runner.handle(new Signal(InteractiveShellRunner.INTERRUPT_SIGNAL));

        // then
        verify(fakeShell).execute("RETURN 1;");
        verify(fakeShell).reset();
        verify(boltStateHandler).reset();
    }

    private static class FakeInterruptableShell extends CypherShell {
        private AtomicReference<Thread> executionThread = new AtomicReference<>();

        FakeInterruptableShell(@Nonnull Logger logger,
                               @Nonnull BoltStateHandler boltStateHandler) {
            super(logger, boltStateHandler, mock(PrettyPrinter.class), new ShellParameterMap());
        }

        @Override
        public void execute(@Nonnull String statement) throws ExitException, CommandException {
            try {
                executionThread.set(Thread.currentThread());
                Thread.sleep(10_000L);
            } catch (InterruptedException ignored) {
                throw new CommandException("execution interrupted");
            }
        }

        @Override
        public void reset() {
            // Do whatever usually happens
            super.reset();
            // But also simulate reset by interrupting the thread
            executionThread.get().interrupt();
        }

        @Override
        public String getActiveDatabaseAsSetByUser() {
            return ABSENT_DB_NAME;
        }

        @Override
        public String getActualDatabaseAsReportedByServer() {
            return DEFAULT_DEFAULT_DB_NAME;
        }
    }
}
