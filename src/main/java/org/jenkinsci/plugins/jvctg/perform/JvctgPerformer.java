package org.jenkinsci.plugins.jvctg.perform;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Throwables.propagate;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.logging.Level.SEVERE;
import static org.jenkinsci.plugins.jvctg.config.CredentialsHelper.findCredentials;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_COMMENTONLYCHANGEDCONTENT;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_CREATECOMMENTWITHALLSINGLEFILECOMMENTS;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_CREATESINGLEFILECOMMENTS;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_GITHUBURL;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_OAUTH2TOKEN;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_PASSWORD;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_PULLREQUESTID;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_REPOSITORYNAME;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_REPOSITORYOWNER;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_USERNAME;
import static org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfigHelper.FIELD_USERNAMEPASSWORDCREDENTIALSID;
import static se.bjurr.violations.comments.github.lib.ViolationCommentsToGitHubApi.violationCommentsToGitHubApi;
import static se.bjurr.violations.lib.ViolationsReporterApi.violationsReporterApi;
import static se.bjurr.violations.lib.parsers.FindbugsParser.setFindbugsMessagesXml;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.List;
import java.util.logging.Logger;

import org.jenkinsci.plugins.jvctg.config.ViolationConfig;
import org.jenkinsci.plugins.jvctg.config.ViolationsToGitHubConfig;
import org.jenkinsci.remoting.RoleChecker;

import se.bjurr.violations.lib.model.Violation;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.io.CharStreams;

public class JvctgPerformer {

 @VisibleForTesting
 public static void doPerform(ViolationsToGitHubConfig config, File workspace, TaskListener listener)
   throws MalformedURLException {
  if (isNullOrEmpty(config.getPullRequestId())) {
   listener.getLogger().println("No pull request id defined, will not send violation comments to GitHub.");
   return;
  }
  Integer pullRequestIdInt = Integer.valueOf(config.getPullRequestId());

  List<Violation> allParsedViolations = newArrayList();
  for (ViolationConfig violationConfig : config.getViolationConfigs()) {
   List<Violation> parsedViolations = violationsReporterApi()//
     .findAll(violationConfig.getReporter())//
     .inFolder(workspace.getAbsolutePath())//
     .withPattern(violationConfig.getPattern())//
     .violations();
   allParsedViolations.addAll(parsedViolations);
   listener.getLogger().println("Found " + parsedViolations.size() + " violations from " + violationConfig + ".");
  }

  String oAuth2Token = null;
  String username = null;
  String password = null;
  if (config.isUseOAuth2Token()) {
   oAuth2Token = checkNotNull(emptyToNull(config.getOAuth2Token()), "OAuth2Token");
   listener.getLogger().println("Using OAuth2Token");
  } else if (config.isUseUsernamePassword()) {
   username = checkNotNull(emptyToNull(config.getUsername()), "username");
   password = checkNotNull(emptyToNull(config.getPassword()), "password");
   listener.getLogger().println("Using username / password");
  } else if (config.isUseUsernamePasswordCredentials()) {
   if (!isNullOrEmpty(config.getUsernamePasswordCredentialsId())) {
    Optional<StandardUsernamePasswordCredentials> credentials = findCredentials(config
      .getUsernamePasswordCredentialsId());
    if (credentials.isPresent()) {
     username = checkNotNull(emptyToNull(credentials.get().getUsername()), "username");
     password = checkNotNull(emptyToNull(credentials.get().getPassword().getPlainText()), "password");
     listener.getLogger().println("Using credentials");
    } else {
     listener.getLogger().println("Credentials not found!");
     return;
    }
   } else {
    listener.getLogger().println("No credentials selected!");
    return;
   }
  } else {
   listener.getLogger().println(
     "No OAuth2 token and no username/password specified. Will not comment any pull request.");
   return;
  }

  listener.getLogger().println(
    "Will comment PR " + config.getRepositoryOwner() + "/" + config.getRepositoryName() + "/"
      + config.getPullRequestId() + " on " + config.getGitHubUrl());

  try {
   violationCommentsToGitHubApi()//
     .withoAuth2Token(oAuth2Token)//
     .withUsername(username)//
     .withPassword(password)//
     .withGitHubUrl(config.getGitHubUrl())//
     .withPullRequestId(pullRequestIdInt)//
     .withRepositoryName(config.getRepositoryName())//
     .withRepositoryOwner(config.getRepositoryOwner())//
     .withViolations(allParsedViolations)//
     .withCreateCommentWithAllSingleFileComments(config.getCreateCommentWithAllSingleFileComments())//
     .withCreateSingleFileComments(config.getCreateSingleFileComments())//
     .withCommentOnlyChangedContent(config.getCommentOnlyChangedContent())//
     .toPullRequest();
  } catch (Exception e) {
   listener.getLogger().println(e.getMessage());
   Logger.getLogger(JvctgPerformer.class.getName()).log(SEVERE, "", e);
  }
 }

 public static void jvctsPerform(final ViolationsToGitHubConfig configUnexpanded, FilePath fp, Run<?, ?> build,
   final TaskListener listener) {
  try {
   EnvVars env = build.getEnvironment(listener);
   final ViolationsToGitHubConfig configExpanded = expand(configUnexpanded, env);
   listener.getLogger().println("---");
   listener.getLogger().println("--- Jenkins Violation Comments to GitHub ---");
   listener.getLogger().println("---");
   logConfiguration(configExpanded, build, listener);

   listener.getLogger().println("Running Jenkins Violation Comments To GitHub");
   listener.getLogger().println("Will comment " + configExpanded.getPullRequestId());

   fp.act(new FileCallable<Void>() {

    private static final long serialVersionUID = 6166111757469534436L;

    @Override
    public void checkRoles(RoleChecker checker) throws SecurityException {

    }

    @Override
    public Void invoke(File workspace, VirtualChannel channel) throws IOException, InterruptedException {
     setupFindBugsMessages();
     listener.getLogger().println("Workspace: " + workspace.getAbsolutePath());
     doPerform(configExpanded, workspace, listener);
     return null;
    }
   });
  } catch (Exception e) {
   listener.getLogger().println(e.getMessage());
   Logger.getLogger(JvctgPerformer.class.getName()).log(SEVERE, "", e);
   return;
  }
 }

 private static void logConfiguration(ViolationsToGitHubConfig config, Run<?, ?> build, TaskListener listener) {
  listener.getLogger().println(FIELD_GITHUBURL + ": " + config.getGitHubUrl());
  listener.getLogger().println(FIELD_REPOSITORYOWNER + ": " + config.getRepositoryOwner());
  listener.getLogger().println(FIELD_REPOSITORYNAME + ": " + config.getRepositoryName());
  listener.getLogger().println(FIELD_PULLREQUESTID + ": " + config.getPullRequestId());

  listener.getLogger().println(
    FIELD_USERNAMEPASSWORDCREDENTIALSID + ": " + !isNullOrEmpty(config.getUsernamePasswordCredentialsId()));
  listener.getLogger().println(FIELD_USERNAME + ": " + !isNullOrEmpty(config.getUsername()));
  listener.getLogger().println(FIELD_PASSWORD + ": " + !isNullOrEmpty(config.getPassword()));
  listener.getLogger().println(FIELD_OAUTH2TOKEN + ": " + !isNullOrEmpty(config.getOAuth2Token()));

  listener.getLogger().println(FIELD_CREATESINGLEFILECOMMENTS + ": " + config.getCreateSingleFileComments());
  listener.getLogger().println(
    FIELD_CREATECOMMENTWITHALLSINGLEFILECOMMENTS + ": " + config.getCreateCommentWithAllSingleFileComments());
  listener.getLogger().println(FIELD_COMMENTONLYCHANGEDCONTENT + ": " + config.getCommentOnlyChangedContent());

  for (ViolationConfig violationConfig : config.getViolationConfigs()) {
   listener.getLogger().println(violationConfig.getReporter() + " with pattern " + violationConfig.getPattern());
  }
 }

 private static void setupFindBugsMessages() {
  try {
   String findbugsMessagesXml = CharStreams.toString(new InputStreamReader(JvctgPerformer.class
     .getResourceAsStream("findbugs-messages.xml")));
   setFindbugsMessagesXml(findbugsMessagesXml);
  } catch (IOException e) {
   propagate(e);
  }
 }

 /**
  * Makes sure any Jenkins variable, used in the configuration fields, are
  * evaluated.
  */
 @VisibleForTesting
 static ViolationsToGitHubConfig expand(ViolationsToGitHubConfig config, EnvVars environment) {
  ViolationsToGitHubConfig expanded = new ViolationsToGitHubConfig();
  expanded.setGitHubUrl(environment.expand(config.getGitHubUrl()));
  expanded.setUsername(environment.expand(config.getUsername()));
  expanded.setPassword(environment.expand(config.getPassword()));
  expanded.setoAuth2Token(environment.expand(config.getOAuth2Token()));
  expanded.setPullRequestId(environment.expand(config.getPullRequestId()));
  expanded.setRepositoryName(environment.expand(config.getRepositoryName()));
  expanded.setRepositoryOwner(environment.expand(config.getRepositoryOwner()));
  expanded.setCreateCommentWithAllSingleFileComments(config.getCreateCommentWithAllSingleFileComments());
  expanded.setCreateSingleFileComments(config.getCreateSingleFileComments());
  expanded.setCommentOnlyChangedContent(config.getCommentOnlyChangedContent());
  expanded.setUsernamePasswordCredentialsId(config.getUsernamePasswordCredentialsId());
  expanded.setUseUsernamePassword(config.isUseUsernamePassword());
  expanded.setUseOAuth2Token(config.isUseOAuth2Token());
  expanded.setUseUsernamePasswordCredentials(config.isUseUsernamePasswordCredentials());
  for (ViolationConfig violationConfig : config.getViolationConfigs()) {
   ViolationConfig p = new ViolationConfig();
   p.setPattern(environment.expand(violationConfig.getPattern()));
   p.setReporter(violationConfig.getReporter());
   expanded.getViolationConfigs().add(p);
  }
  return expanded;
 }
}
