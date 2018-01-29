package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jenkinsci.plugins.Constants.LIST;
import static org.jenkinsci.plugins.Constants.TELERIK_API_TESTING_RUNNER_EXE;
import static org.jenkinsci.plugins.Constants.TEST;
import static org.jenkinsci.plugins.Utils.fileExists;
import static org.jenkinsci.plugins.Utils.isEmpty;

@SuppressWarnings("unused")
public class TestBuilder extends Builder implements SimpleBuildStep {


    private String apiRunnerPath;
    private String project;
    private String test;
    private String startFrom;
    private TestType testType = TestType.SINGLE_TEST;
    private String stopAfter;
    private String variable;
    private boolean dontSaveContexts;
    private boolean testAsUnit;
    private boolean isWindows = false;


    @DataBoundConstructor
    public TestBuilder(String apiRunnerPath,
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
        if (System.getProperty("os.name").toLowerCase().contains("windows")){
            this.isWindows = true;
        }

        String workspacePath = run.getEnvironment(listener).get("WORKSPACE", null);
        String outputFileName = "ApiResults-" + System.currentTimeMillis() + ".xml";
        String command = buildCommand(workspacePath, outputFileName);
        listener.getLogger().println("command: " + command);
        prepareWorkspace(workspacePath);
        Runtime rt = Runtime.getRuntime();
        Process proc = rt.exec(command);

        BufferedReader stdInput = new BufferedReader(new
                InputStreamReader(proc.getInputStream()));

        BufferedReader stdError = new BufferedReader(new
                InputStreamReader(proc.getErrorStream()));

        listener.getLogger().println("Command output:\n");
        String s = null;
        while ((s = stdInput.readLine()) != null) {
            listener.getLogger().println(s);
        }

        listener.getLogger().println("STD Error output (if any):\n");
        while ((s = stdError.readLine()) != null) {
            listener.error(s);
        }

        String fullOutputName = workspace + File.separator + Constants.API_STUDIO_RESULTS_DIR + File.separator + outputFileName ;
        if (!fileExists(fullOutputName)) {
            listener.error("Result file doesn't exists: " + fullOutputName);
            run.setResult(Result.FAILURE);
        }
    }

    private void prepareWorkspace(String workspace) {
        File index = new File(workspace + File.separator + Constants.API_STUDIO_RESULTS_DIR);
        if (!index.exists()) {
            index.mkdir();
        } else {
            String[] entries = index.list();
            for (String s : entries) {
                File currentFile = new File(index.getPath(), s);
                currentFile.delete();
            }
            if (!index.exists()) {
                index.mkdir();
            }
        }
    }

    private String buildCommand(String workspace, String outputFileName) {
        StringBuilder sb = new StringBuilder();
        sb.append(this.normalizeExecutable(this.apiRunnerPath));

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

    @SuppressWarnings("unused")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @SuppressWarnings("unused")
        public FormValidation doCheckapiRunnerPath(@QueryParameter String apiRunnerPath) throws IOException, ServletException {
            if (isEmpty(apiRunnerPath)) {
                return FormValidation.error(Messages.TestBuilder_DescriptorImpl_errors_zero_apiStudioTestRunnerPath());
            } else {
                File f = new File(apiRunnerPath);
                if (!f.exists()) {
                    return FormValidation.error(Messages.TestBuilder_DescriptorImpl_errors_notFound_apiStudioTestRunnerPath());
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
            return Messages.TestBuilder_DescriptorImpl_DisplayName();
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



