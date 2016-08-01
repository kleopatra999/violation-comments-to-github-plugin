package org.jenkinsci.plugins.jvctg;

import static hudson.tasks.BuildStepMonitor.NONE;
import static org.jenkinsci.plugins.jvctg.perform.JvctgPerformer.jvctsPerform;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nonnull;

import jenkins.tasks.SimpleBuildStep;

import org.jenkinsci.plugins.jvctg.config.ViolationConfig;
import org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfig;
import org.kohsuke.stapler.DataBoundConstructor;

public class ViolationsToGitHubRecorder extends Recorder implements SimpleBuildStep {
 @Extension
 public static final BuildStepDescriptor<Publisher> DESCRIPTOR = new ViolationsToGitHubDescriptor();
 private ViolationsToGitHubConfig config;

 public ViolationsToGitHubRecorder() {
 }

 @DataBoundConstructor
 public ViolationsToGitHubRecorder(boolean createSingleFileComments, boolean createCommentWithAllSingleFileComments,
   String repositoryName, String repositoryOwner, String password, String username, String oAuth2Token,
   String pullRequestId, String gitHubUrl, boolean commentOnlyChangedContent, List<ViolationConfig> violationConfigs,
   String usernamePasswordCredentialsId, boolean useOAuth2Token, boolean useUsernamePasswordCredentials,
   boolean useUsernamePassword) {

  this.config = new ViolationsToGitHubConfig(createSingleFileComments, createCommentWithAllSingleFileComments,
    repositoryName, repositoryOwner, password, username, oAuth2Token, pullRequestId, gitHubUrl,
    commentOnlyChangedContent, violationConfigs, usernamePasswordCredentialsId, useOAuth2Token,
    useUsernamePasswordCredentials, useUsernamePassword);
 }

 public ViolationsToGitHubConfig getConfig() {
  return this.config;
 }

 @Override
 public BuildStepDescriptor<Publisher> getDescriptor() {
  return DESCRIPTOR;
 }

 @Override
 public BuildStepMonitor getRequiredMonitorService() {
  return NONE;
 }

 @Override
 public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath filePath, @Nonnull Launcher launcher,
   @Nonnull TaskListener listener) throws InterruptedException, IOException {

  ViolationsToGitHubConfig combinedConfig = new ViolationsToGitHubConfig(this.config);
  ViolationsToGitHubConfiguration defaults = ViolationsToGitHubConfiguration.get();

  combinedConfig.applyDefaults(defaults);

  jvctsPerform(combinedConfig, filePath, build, listener);
 }

 public void setConfig(ViolationsToGitHubConfig config) {
  this.config = config;
 }

}
