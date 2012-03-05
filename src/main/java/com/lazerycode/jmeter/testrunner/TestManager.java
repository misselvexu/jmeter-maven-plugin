package com.lazerycode.jmeter.testrunner;

import com.lazerycode.jmeter.IncludesComparator;
import com.lazerycode.jmeter.JMeterMojo;
import com.lazerycode.jmeter.RemoteConfig;
import com.lazerycode.jmeter.UtilityFunctions;
import com.lazerycode.jmeter.configuration.JMeterArgumentsArray;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.jmeter.JMeter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.tools.ant.DirectoryScanner;

import java.io.*;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * TestManager encapsules functions that gather JMeter Test files and execute the tests
 */
public class TestManager extends JMeterMojo {

    private JMeterArgumentsArray testArgs;
    private File jmeterLog;
    private File logsDir;
    private File testFilesDirectory;
    private List<String> testFilesIncluded;
    private List<String> testFilesExcluded;
    private boolean suppressJMeterOutput;
    private boolean remoteStop = false;
    private boolean remoteStartAll = false;
    private boolean remoteStartAndStopOnce = true;
    private String remoteStart = null;
    private int exitCheckPause = 2000;
    private boolean useOldTestEndDetection = false;

    public TestManager(JMeterArgumentsArray testArgs, File logsDir, File testFilesDirectory, List<String> testFiles, List<String> excludeTestFiles, boolean suppressJMeterOutput) {
        this.testArgs = testArgs;
        this.logsDir = logsDir;
        this.testFilesDirectory = testFilesDirectory;
        this.testFilesIncluded = testFiles;
        this.testFilesExcluded = excludeTestFiles;
        this.suppressJMeterOutput = suppressJMeterOutput;
    }

    /**
     * Set how long to wait for JMeter to clean up it's threads after a test run.
     *
     * @param value int
     */
    public void setExitCheckPause(int value) {
        this.exitCheckPause = value;
    }

    public void setTestEndDetection(boolean value){
        this.useOldTestEndDetection = value;
    }

    /**
     * Set remote configuration
     *
     * @param remoteConfig
     */
    public void setRemoteConfig(RemoteConfig remoteConfig) {
        this.remoteStop = remoteConfig.isStop();
        this.remoteStartAll = remoteConfig.isStartAll();
        this.remoteStartAndStopOnce = remoteConfig.isStartAndStopOnce();
        if (!UtilityFunctions.isNotSet(remoteConfig.getStart())) {
            this.remoteStart = remoteConfig.getStart();
        }
    }

    /**
     * Executes all tests and returns the resultFile names
     *
     * @return the list of resultFile names
     * @throws MojoExecutionException
     */
    public List<String> executeTests() throws MojoExecutionException {
        List<String> tests = generateTestList();
        List<String> results = new ArrayList<String>();
        for (String file : tests) {
            if (!this.remoteStartAndStopOnce || tests.get(tests.size() - 1).equals(file)) {
                testArgs.setRemoteStop(this.remoteStop);
            }
            if (!this.remoteStartAndStopOnce || tests.get(0).equals(file)) {
                testArgs.setRemoteStartAll(this.remoteStartAll);
                testArgs.setRemoteStart(this.remoteStart);
            }
            results.add(executeSingleTest(new File(testFilesDirectory, file)));
        }
        return results;
    }

    //=============================================================================================

    /**
     * Capture System.exit commands so that we can check to see if JMeter is trying to kill us without warning.
     *
     * @return old SecurityManager so that we can switch back to normal behaviour.
     */
    private SecurityManager overrideSecurityManager() {
        SecurityManager oldManager = System.getSecurityManager();
        System.setSecurityManager(new SecurityManager() {

            @Override
            public void checkExit(int status) {
                throw new ExitException(status);
            }

            @Override
            public void checkPermission(Permission perm, Object context) {
            }

            @Override
            public void checkPermission(Permission perm) {
            }
        });
        return oldManager;
    }

    /**
     * Override System.exit(0) to ensure JMeter doesn't kill us without warning.
     *
     * @return old UncaughtExceptionHandler so that we can switch back to normal behaviour.
     */
    private Thread.UncaughtExceptionHandler overrideUncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

            public void uncaughtException(Thread t, Throwable e) {
                if (e instanceof ExitException && ((ExitException) e).getCode() == 0) {
                    return; // Ignore
                }
                getLog().error("Error in thread " + t.getName());
            }
        });
        return oldHandler;
    }

    /**
     * Executes a single JMeter test by building up a list of command line
     * parameters to pass to JMeter.start().
     *
     * @param test JMeter test XML
     * @return the report file names.
     * @throws org.apache.maven.plugin.MojoExecutionException
     *          Exception
     */
    private String executeSingleTest(File test) throws MojoExecutionException {

        try {
            getLog().info(" ");
            testArgs.setTestFile(test);
            //Delete results file if it already exists
            new File(testArgs.getResultsFileName()).delete();
            getLog().debug("JMeter is called with the following command line arguments: " + UtilityFunctions.humanReadableCommandLineOutput(testArgs.buildArgumentsArray()));
            SecurityManager oldSecurityManager = overrideSecurityManager();
            Thread.UncaughtExceptionHandler oldExceptionHandler = overrideUncaughtExceptionHandler();
            PrintStream originalOut = System.out;
            try {
                // This mess is necessary because the only way to know when
                // JMeter is done is to wait for its test end message!                
                setJMeterLogFile(test.getName() + ".log");
                getLog().info("Executing test: " + test.getName());
                //Suppress JMeter's annoying System.out messages
                if (suppressJMeterOutput) System.setOut(new PrintStream(new NullOutputStream()));
                new JMeter().start(testArgs.buildArgumentsArray());

                //TODO Investigate the use of a listener here (Looks like JMeter reports startup and shutdown to a listener when it finishes a test...
                if (this.useOldTestEndDetection) {
                    while (!checkForEndOfTest(new BufferedReader(new FileReader(jmeterLog)))) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                } else {
                    while (hasTestFinished()) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }


            } catch (ExitException e) {
                if (e.getCode() != 0) {
                    throw new MojoExecutionException("Test failed", e);
                }
            } finally {
                try {
                    //Wait for JMeter to clean up threads.
                    Thread.sleep(this.exitCheckPause);
                } catch (InterruptedException e) {
                    getLog().warn("Something went wrong during Thread cleanup, we may be leaving something running...");
                }
                System.setSecurityManager(oldSecurityManager);
                Thread.setDefaultUncaughtExceptionHandler(oldExceptionHandler);
                System.setOut(originalOut);
                getLog().info("Completed Test: " + test.getName());
            }
            return testArgs.getResultsFileName();
        } catch (IOException e) {
            throw new MojoExecutionException("Can't execute test", e);
        }
    }

    private boolean hasTestFinished(){
        // TODO Put listener check here
        try {
            //Temp for testing purposes
            Thread.sleep(5000);
        } catch (InterruptedException e) {

        }
        return false;
    }

    /**
     * Check JMeter logfile (provided as a BufferedReader) for End message.
     *
     * @param in JMeter logfile
     * @return true if test ended
     * @throws MojoExecutionException exception
     */
    @Deprecated
    private boolean checkForEndOfTest(BufferedReader in) throws MojoExecutionException {
        boolean testEnded = false;
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("Test has ended") || line.contains("Interrupting RMI Reaper")) {
                    testEnded = true;
                    break;
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Can't read log file", e);
        }
        return testEnded;
    }

    /**
     * Create the jmeter.log file and set the log_file system property for JMeter to pick up
     *
     * @param value
     */
    private void setJMeterLogFile(String value) {
        this.jmeterLog = new File(this.logsDir + File.separator + value);
        System.setProperty("log_file", this.jmeterLog.getAbsolutePath());
    }

    /**
     * Scan Project directories for JMeter Test Files according to includes and excludes
     *
     * @return found JMeter tests
     */
    private List<String> generateTestList() {
        List<String> jmeterTestFiles = new ArrayList<String>();
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(this.testFilesDirectory);
        scanner.setIncludes(this.testFilesIncluded == null ? new String[]{"**/*.jmx"} : this.testFilesIncluded.toArray(new String[jmeterTestFiles.size()]));
        if (this.testFilesExcluded != null) {
            scanner.setExcludes(this.testFilesExcluded.toArray(new String[testFilesExcluded.size()]));
        }
        scanner.scan();
        final List<String> includedFiles = Arrays.asList(scanner.getIncludedFiles());
        Collections.sort(includedFiles, new IncludesComparator(this.testFilesIncluded));
        jmeterTestFiles.addAll(includedFiles);
        return jmeterTestFiles;
    }
}