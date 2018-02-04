package org.jenkinsci.plugins.teststudioapitesting;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jenkinsci.plugins.teststudioapitesting.Constants.TELERIK_API_TESTING_RUNNER_EXE;
import static org.jenkinsci.plugins.teststudioapitesting.Utils.isEmpty;

@SuppressWarnings("unused")
public class TestStudioAPITestBuilder extends Builder implements SimpleBuildStep, Serializable {

    private static final long serialVersionUID = 82342459898912L;

    private String apiRunnerPath;
    private String project;
    private String test;
    private String startFrom;
    private String stopAfter;
    private String variable;
    private boolean dontSaveContexts;
    private boolean testAsUnit;
    private boolean isWindows = false;


    @DataBoundConstructor
    public TestStudioAPITestBuilder(String apiRunnerPath,
                       String project,
                       String test,
                       String startFrom,
                       String stopAfter,
                       String variable,
                       boolean dontSaveContexts,
                       boolean testAsUnit) {
        this.apiRunnerPath = apiRunnerPath;
        this.project = project;
        this.test = test;
        this.startFrom = startFrom;
        this.testAsUnit = testAsUnit;
        this.stopAfter = stopAfter;
        this.variable = variable;
        this.dontSaveContexts = dontSaveContexts;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {

        MyCallable task = new MyCallable(String.valueOf(workspace),
                Constants.API_STUDIO_RESULTS_DIR,
                this.apiRunnerPath,
                this.testAsUnit,
                this.project,
                this.test,
                this.dontSaveContexts,
                variable);

        String result = launcher.getChannel().call(task);
        listener.getLogger().println(result);
        if (result != null && result.contains(" Finished.") && result.contains("[INFO]  Project compile completed.")){
            run.setResult(Result.SUCCESS);
        } else {
            run.setResult(Result.FAILURE);
        }
    }


    class MyCallable implements Callable<String, IOException> {
        private static final long serialVersionUID = 439832542381L;

        private String workspace;
        private String resultsDir;
        private String apiRunnerPath;
        private String project;
        private String test;
        private String variable;

        private boolean testAsUnit;
        private boolean isWindows;
        private boolean dontSaveContexts;

        public MyCallable(String workspace,
                          String resultsDir,
                          String apiRunnerPath,
                          boolean testAsUnit,
                          String project,
                          String test,
                          boolean dontSaveContexts,
                          String variable){
            this.workspace = workspace;
            this.resultsDir = resultsDir;
            this.testAsUnit = testAsUnit;
            this.apiRunnerPath = apiRunnerPath;
            this.project = project;
            this.test = test;
            this.dontSaveContexts = dontSaveContexts;
            this.variable = variable;
        }

        @Override
        public void checkRoles(RoleChecker roleChecker) throws SecurityException {

        }

        @Override
        public String call() throws IOException {

            StringBuilder output = new StringBuilder();

            if (System.getProperty("os.name").toLowerCase().contains("windows")){
                this.isWindows = true;
                output.append("\nRunning OS: Windows\n");
            } else {
                output.append("\nRunning OS: Linux\n");
            }

            String outputFileName = "ApiResults-" + System.currentTimeMillis() + ".xml";
            String command = buildCommand(this.workspace, outputFileName);
            output.append("\nCommand: \n" + command + "\n");

            Runtime rt = Runtime.getRuntime();
            Process proc = rt.exec(command);

            BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));

            BufferedReader stdError = new BufferedReader(new
                    InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8));

            try {
                output.append("\nCommand output:\n");
                String s = null;
                while ((s = stdInput.readLine()) != null) {
                    output.append(s + "\n");
                }

                output.append("\nSTD Error output (if any):\n");
                while ((s = stdError.readLine()) != null) {
                    output.append(s + "\n");
                }
            }
            finally {
                stdInput.close();
                stdError.close();
            }

            output.append("\nCommand exit code: " + proc.exitValue() +" \n");
            return output.toString();
        }
        private String buildCommand(String workspace, String outputFileName) {
            StringBuilder sb = new StringBuilder();
            sb.append("\"" + this.normalizeExecutable(this.apiRunnerPath) + "\"");

            sb.append(" test ");
            if (!isEmpty(this.project)) {
                sb.append("-p \"");
                sb.append(normalizePath(workspace, this.project));
                sb.append("\"");
            }

            if (!isEmpty(this.test)) {
                String[] tests = this.test.split("\n");
                for (int idx = 0; idx < tests.length; idx++){
                    if (!isEmpty(tests[idx])) {
                        if (tests[idx].startsWith("." + File.separator)) {
                            sb.append(" -t \"");
                        } else {
                            sb.append(" -t \"." + File.separator);
                        }
                        sb.append(tests[idx]);
                        sb.append("\"");
                    }
                }
            }

            sb.append(" ");
            sb.append("-o \"");
            sb.append(normalizePath(workspace, Constants.API_STUDIO_RESULTS_DIR + File.separator + outputFileName));
            sb.append("\"");

            if (!this.dontSaveContexts) {
                sb.append(" --save-contexts");
            }

            sb.append(" --verbose-compile");
            sb.append(" -f ");
            if (this.testAsUnit) {
                sb.append("junit");
            } else {
                sb.append("junitsteps");
            }

            if (!isEmpty(this.variable)) {
                String[] vars = this.variable.split("\n");
                String prefix = " ";
                for (int idx = 0; idx < vars.length; idx++){
                    sb.append(prefix);
                    sb.append("-v ");
                    sb.append(vars[idx]);
                    sb.append(" ");
                    prefix = "";
                }
            }

            return sb.toString();
        }

        private String normalizeExecutable(String apiStudioRunnerPath) {
            String pathToLowerCase = apiStudioRunnerPath.toLowerCase();
            String command = "";
            if (!isWindows){
                command = "mono ";
            }
            if (pathToLowerCase.endsWith(File.separator)) {
                return command + apiStudioRunnerPath + TELERIK_API_TESTING_RUNNER_EXE;
            } else if (!pathToLowerCase.endsWith(TELERIK_API_TESTING_RUNNER_EXE.toLowerCase())) {
                return command + apiStudioRunnerPath + "\\" + TELERIK_API_TESTING_RUNNER_EXE;
            }
            return command + apiStudioRunnerPath;

        }

        private String normalizePath(String workspace, String path){
            String result;
            if (isWindows) {
                Matcher m = Pattern.compile("^\\D:\\\\.*$").matcher(path);
                if (!m.find()) {
                    if (path.startsWith(File.separator)) {
                        result = workspace + path;
                    } else {
                        result = workspace + File.separator + path;
                    }
                } else {
                    result = path;
                }
                if (result.endsWith(File.separator)) {
                    result = result.substring(0, result.length() - 1);
                }
            } else {
                if (path.startsWith(File.separator)) {
                    result = path;
                } else {
                    result = workspace + File.separator + path;
                }
            }
            return result;
        }
    }

    @SuppressWarnings("unused")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @SuppressWarnings("unused")
        public FormValidation doCheckapiRunnerPath(@QueryParameter String apiRunnerPath) throws IOException, ServletException {
            if (isEmpty(apiRunnerPath)) {
                return FormValidation.error(Messages.TestStudioAPITestBuilder_DescriptorImpl_errors_zero_apiStudioTestRunnerPath());
            } else {
                File f = new File(apiRunnerPath);
                if (!f.exists()) {
                    return FormValidation.error(Messages.TestStudioAPITestBuilder_DescriptorImpl_errors_notFound_apiStudioTestRunnerPath());
                }
            }
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.TestStudioAPITestBuilder_DescriptorImpl_DisplayName();
        }

    }

    public String getApiRunnerPath() {
        return apiRunnerPath;
    }

    @DataBoundSetter
    public void setApiRunnerPath(String apiRunnerPath) {
        this.apiRunnerPath = apiRunnerPath;
    }

    public String getProject() {
        return project;
    }

    @DataBoundSetter
    public void setProject(String project) {
        this.project = project;
    }

    public String getTest() {
        return test;
    }

    @DataBoundSetter
    public void setTest(String test) {
        this.test = test;
    }

    public String getStartFrom() {
        return startFrom;
    }

    @DataBoundSetter
    public void setStartFrom(String startFrom) {
        this.startFrom = startFrom;
    }

    public String getStopAfter() {
        return stopAfter;
    }

    @DataBoundSetter
    public void setStopAfter(String stopAfter) {
        this.stopAfter = stopAfter;
    }

    public String getVariable() {
        return variable;
    }

    @DataBoundSetter
    public void setVariable(String variable) {
        this.variable = variable;
    }

    public boolean isDontSaveContexts() {
        return dontSaveContexts;
    }

    @DataBoundSetter
    public void setDontSaveContexts(boolean dontSaveContexts) {
        this.dontSaveContexts = dontSaveContexts;
    }

    public boolean isTestAsUnit() {
        return testAsUnit;
    }

    @DataBoundSetter
    public void setTestAsUnit(boolean testAsUnit) {
        this.testAsUnit = testAsUnit;
    }
}



