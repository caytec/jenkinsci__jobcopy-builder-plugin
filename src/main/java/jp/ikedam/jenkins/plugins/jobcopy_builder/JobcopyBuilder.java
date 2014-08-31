/*
 * The MIT License
 * 
 * Copyright (c) 2012-2013 IKEDA Yasuyuki
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jp.ikedam.jenkins.plugins.jobcopy_builder;

import java.util.List;

import hudson.Extension;
import hudson.XmlFile;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.DescriptorExtensionList;
import hudson.matrix.MatrixProject;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.TopLevelItem;
import hudson.model.BuildListener;
import hudson.model.Job;
import hudson.model.AbstractBuild;
import hudson.model.AbstractItem;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

/**
 * A build step to copy a job.
 * 
 * You can specify additional operations that is performed when copying,
 * and the operations can be extended with plugins using Extension Points.
 */
public class JobcopyBuilder extends Builder implements Serializable
{
    private static final long serialVersionUID = 1L;
    
    private String fromJobName;
    
    /**
     * Returns the name of job to be copied from.
     * 
     * Variable expressions will be expanded.
     * 
     * @return the name of job to be copied from
     */
    public String getFromJobName()
    {
        return fromJobName;
    }
    
    private String toJobName;
    
    /**
     * Returns the name of job to be copied to.
     * 
     * Variable expressions will be expanded.
     * 
     * @return the name of job to be copied to
     */
    public String getToJobName()
    {
        return toJobName;
    }
    
    private boolean overwrite = false;
    
    /**
     * Returns whether to overwrite an existing job.
     * 
     * If the copied-to job is already exists,
     * jobcopy build step works as following depending on this value.
     * <table>
     *     <tr>
     *         <th>isOverwrite</th>
     *         <th>behavior</th>
     *     </tr>
     *     <tr>
     *         <td>true</td>
     *         <td>Overwrite the configuration of the existing job.</td>
     *     </tr>
     *     <tr>
     *         <td>false</td>
     *         <td>Build fails.</td>
     *     </tr>
     * </table>
     * 
     * @return whether to overwrite an existing job.
     */
    public boolean isOverwrite()
    {
        return overwrite;
    }
    
    private List<JobcopyOperation> jobcopyOperationList;
    
    /**
     * Returns the list of operations.
     * 
     * @return the list of operations
     */
    public List<JobcopyOperation> getJobcopyOperationList()
    {
        return jobcopyOperationList;
    }
    
    private List<AdditionalFileset> additionalFilesetList;
    
    /**
     * Retuns a list of sets of files to copy additional to JOBNAME/config.xml.
     * 
     * @return the additionalFilesetList
     */
    public List<AdditionalFileset> getAdditionalFilesetList()
    {
        return additionalFilesetList;
    }

    /**
     * Constructor to instantiate from parameters in the job configuration page.
     * 
     * When instantiating from the saved configuration,
     * the object is directly serialized with XStream,
     * and no constructor is used.
     * 
     * @param fromJobName   a name of a job to be copied from. may contains variable expressions.
     * @param toJobName     a name of a job to be copied to. may contains variable expressions.
     * @param overwrite     whether to overwrite if the job to be copied to is already existing.
     * @param jobcopyOperationList
     *                      the list of operations to be performed when copying.
     * @param additionalFilesetList
     *                      the list of sets of files to copy additional to JOBNAME/config.xml.
     */
    @DataBoundConstructor
    public JobcopyBuilder(String fromJobName, String toJobName, boolean overwrite, List<JobcopyOperation> jobcopyOperationList, List<AdditionalFileset> additionalFilesetList)
    {
        this.fromJobName = StringUtils.trim(fromJobName);
        this.toJobName = StringUtils.trim(toJobName);
        this.overwrite = overwrite;
        this.jobcopyOperationList = jobcopyOperationList;
        this.additionalFilesetList = additionalFilesetList;
    }
    
    /**
     * Performs the build step.
     * 
     * @param build
     * @param launcher
     * @param listener
     * @return  whether the process succeeded.
     * @throws IOException
     * @throws InterruptedException
     * @see hudson.tasks.BuildStepCompatibilityLayer#perform(hudson.model.AbstractBuild, hudson.Launcher, hudson.model.BuildListener)
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
        throws IOException, InterruptedException
    {
        ItemGroup<?> context = build.getProject().getRootProject().getParent();
        EnvVars env = build.getEnvironment(listener);
        
        if(StringUtils.isBlank(getFromJobName()))
        {
            listener.getLogger().println("From Job Name is not specified");
            return false;
        }
        if(StringUtils.isBlank(getToJobName()))
        {
            listener.getLogger().println("To Job Name is not specified");
            return false;
        }
        
        // Expand the variable expressions in job names.
        String fromJobNameExpanded = env.expand(getFromJobName());
        String toJobNameExpanded = env.expand(getToJobName());
        
        if(StringUtils.isBlank(fromJobNameExpanded))
        {
            listener.getLogger().println("From Job Name got to a blank");
            return false;
        }
        if(StringUtils.isBlank(toJobNameExpanded))
        {
            listener.getLogger().println("To Job Name got to a blank");
            return false;
        }
        
        listener.getLogger().println(String.format("Copying %s to %s", fromJobNameExpanded, toJobNameExpanded));
        
        // Reteive the job to be copied from.
        TopLevelItem fromJob = Jenkins.getInstance().getItem(fromJobNameExpanded, context, TopLevelItem.class);
        
        if(fromJob == null)
        {
            listener.getLogger().println(String.format("Error: Item '%s 'was not found.", fromJob));
            return false;
        }
        else if(!(fromJob instanceof Job<?,?>))
        {
            listener.getLogger().println(String.format("Error: Item '%s' was found, but is not a job.", fromJob));
            return false;
        }
        
        // Check whether the job to be copied to is already exists.
        TopLevelItem toJob = Jenkins.getInstance().getItem(toJobNameExpanded, context, TopLevelItem.class);
        if(toJob != null){
            listener.getLogger().println(String.format("Already exists: %s", toJobNameExpanded));
            if(!isOverwrite()){
                return false;
            }
            if(!(toJob instanceof AbstractItem))
            {
                listener.getLogger().println("Only AbstractItem can be overwritten: please delete manually, and run copy again");
                return false;
            }
        }
        
        // Retrieve the config.xml of the job copied from.
        listener.getLogger().println(String.format("Fetching configuration of %s...", fromJobNameExpanded));
        
        XmlFile file = ((Job<?,?>)fromJob).getConfigFile();
        String jobConfigXmlString = file.asString();
        String encoding = file.sniffEncoding();
        listener.getLogger().println("Original xml:");
        listener.getLogger().println(jobConfigXmlString);
        
        // Apply additional operations to the retrieved XML.
        if(getJobcopyOperationList() != null)
        {
            for(JobcopyOperation operation: getJobcopyOperationList())
            {
                jobConfigXmlString = operation.perform(jobConfigXmlString, encoding, env, listener.getLogger());
                if(jobConfigXmlString == null)
                {
                    return false;
                }
            }
        }
        listener.getLogger().println("Copied xml:");
        listener.getLogger().println(jobConfigXmlString);
        
        if(toJob == null)
        {
            // Create the job copied to.
            listener.getLogger().println(String.format("Creating %s", toJobNameExpanded));
            InputStream is = new ByteArrayInputStream(jobConfigXmlString.getBytes(encoding)); 
            String parentName = null;
            String itemName = null;
            if(toJobNameExpanded.lastIndexOf('/')  >= 0)
            {
                int pos = toJobNameExpanded.lastIndexOf('/');
                parentName = toJobNameExpanded.substring(0, pos);
                itemName = toJobNameExpanded.substring(pos + 1);
            }
            else
            {
                parentName = null;
                itemName = toJobNameExpanded;
            }
            if(StringUtils.isBlank(parentName) && context == Jenkins.getInstance().getItemGroup())
            {
                toJob = Jenkins.getInstance().createProjectFromXML(itemName, is);
            }
            else
            {
                if(!isFolderPluginInstalled())
                {
                    listener.getLogger().println("Error: Cloudbees folder plugin should be installed to create a project in an item group.");
                    return false;
                }
                Folder folder = null;
                if(!StringUtils.isBlank(parentName))
                {
                    folder = Jenkins.getInstance().getItem(parentName, context, Folder.class);
                    if(folder == null)
                    {
                        listener.getLogger().println(String.format("Error: Target folder '%s' was not found.", parentName));
                        return false;
                    }
                }
                else
                {
                    if(!(context instanceof Folder))
                    {
                        listener.getLogger().println("Error: Jobcopy builder works only with cloudbees-folder");
                        return false;
                    }
                    folder = (Folder)context;
                }
                toJob = folder.createProjectFromXML(itemName, is);
            }
            if(toJob == null)
            {
                listener.getLogger().println(String.format("Failed to create %s", toJobNameExpanded));
                return false;
            }
        }
        else
        {
            listener.getLogger().println(String.format("Updating %s", toJobNameExpanded));
            AbstractItem target = (AbstractItem)toJob;
            InputStream is = new ByteArrayInputStream(jobConfigXmlString.getBytes(encoding));
            
            String combinationFilter = null;
            if(target instanceof MatrixProject)
            {
                MatrixProject matrix = (MatrixProject)target;
                // Workaround for the case combinationFilter is removed.
                // In that case, updateByXml does not update combinationFilter,
                // for combinationFilter is not written in XML.
                // So reset it here in advance. 
                // It will be overwritten if defined.
                combinationFilter = matrix.getCombinationFilter();
                matrix.setCombinationFilter(null);
            }
            
            try
            {
                target.updateByXml((Source)new StreamSource(is));
            }
            catch(IOException e)
            {
                if(combinationFilter != null)
                {
                    // recover combinationFilter.
                    MatrixProject matrix = (MatrixProject)target;
                    matrix.setCombinationFilter(combinationFilter);
                }
                throw e;
            }
        }
        
        boolean failed = false;
        
        if(getAdditionalFilesetList() != null && !getAdditionalFilesetList().isEmpty())
        {
            listener.getLogger().println("Copying Additional Files...");
            for(AdditionalFileset fileset: getAdditionalFilesetList())
            {
                if(!fileset.perform(toJob, fromJob, env, listener.getLogger()))
                {
                    failed = true;
                }
            }
            
            // Do null update to reload the configuration.
            AbstractItem target = (AbstractItem)toJob;
            target.updateByXml((Source)new StreamSource(target.getConfigFile().readRaw()));
        }
        
        // add the information of jobs copied from and to to the build.
        build.addAction(new CopiedjobinfoAction(fromJob, toJob, failed));
        
        return true;
    }
    
    /**
     * @return whether cloudbees-folder installed
     */
    public static boolean isFolderPluginInstalled()
    {
        hudson.Plugin plugin = Jenkins.getInstance().getPlugin("cloudbees-folder");
        return plugin != null ? plugin.getWrapper().isActive() : false;
    }
    
    /**
     * The internal class to work with views.
     * 
     * The following files are used (put in main/resource directory in the source tree).
     * <dl>
     *     <dt>config.jelly</dt>
     *         <dd>shown as a part of a job configuration page.</dd>
     * </dl>
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder>
    {
        /**
         * Returns the display name
         * 
         * Displayed in the "Add build step" dropdown in a job configuration page. 
         * 
         * @return the display name
         * @see hudson.model.Descriptor#getDisplayName()
         */
        @Override
        public String getDisplayName()
        {
            return Messages.JobCopyBuilder_DisplayName();
        }
        
        /**
         * Test whether this build step can be applied to the specified job type.
         * 
         * This build step works for any type of jobs, for always returns true.
         * 
         * @param jobType the type of the job to be tested.
         * @return true
         * @see hudson.tasks.BuildStepDescriptor#isApplicable(java.lang.Class)
         */
        @SuppressWarnings("rawtypes")
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType)
        {
            return true;
        }
        
        /**
         * Returns all the available JobcopyOperation.
         * 
         * Used for the contents of "Add Copy Operation" dropdown.
         * 
         * @return the list of JobcopyOperation
         */
        public DescriptorExtensionList<JobcopyOperation,Descriptor<JobcopyOperation>> getJobcopyOperationDescriptors()
        {
            return JobcopyOperation.all();
        }
        
        /**
         * Returns the list of jobs.
         * 
         * Used for the autocomplete of From Job Name.
         * 
         * @return the list of names of jobs
         */
        public ComboBoxModel doFillFromJobNameItems(@AncestorInPath AbstractProject<?,?> project)
        {
            final ItemGroup<?> context = (project != null)?project.getParent():Jenkins.getInstance().getItemGroup();
            return new ComboBoxModel(Collections2.transform(
                    Jenkins.getInstance().getAllItems(),
                    new Function<Item, String>()
                    {
                        public String apply(Item input)
                        {
                            return input.getRelativeNameFrom(context);
                        }
                    }
            ));
        }
        
        /**
         * Returns whether the value contains variable.
         * 
         * @param value value to be tested.
         * @return whether the value contains variable
         */
        private boolean containsVariable(String value)
        {
            if(StringUtils.isBlank(value) || !value.contains("$")){
                // apparently contains no variable.
                return false;
            }
            
            return true;
        }
        
        /**
         * Validate "From Job Name" or "To Job Name" field.
         * 
         * Returns as following:
         * <table>
         *     <tr>
         *         <th>jobName</th>
         *         <th>warnIfExists</th>
         *         <th>warnIfNotExists</th>
         *         <th>Returns</th>
         *     </tr>
         *     <tr>
         *         <td>Blank</th>
         *         <th>any</th>
         *         <th>any</th>
         *         <td>error</td>
         *     </tr>
         *     <tr>
         *         <td>value containing variables</th>
         *         <th>any</th>
         *         <th>any</th>
         *         <td>ok</td>
         *     </tr>
         *     <tr>
         *         <td>existing job</th>
         *         <th>false</th>
         *         <th>any</th>
         *         <td>ok</td>
         *     </tr>
         *     <tr>
         *         <td>existing job</th>
         *         <th>true</th>
         *         <th>any</th>
         *         <td>warning</td>
         *     </tr>
         *     <tr>
         *         <td>non existing job</th>
         *         <th>any</th>
         *         <th>false</th>
         *         <td>ok</td>
         *     </tr>
         *     <tr>
         *         <td>non existing job</th>
         *         <th>any</th>
         *         <th>true</th>
         *         <td>warning</td>
         *     </tr>
         * </table>
         * 
         * @param jobName
         * @param warnIfExists
         * @param warnIfNotExists
         * @return
         */
        public FormValidation doCheckJobName(AbstractProject<?,?> project, String jobName, boolean warnIfExists, boolean warnIfNotExists)
        {
            ItemGroup<?> context = (project != null)?project.getParent():Jenkins.getInstance().getItemGroup();
            jobName = StringUtils.trim(jobName);
            
            if(StringUtils.isBlank(jobName))
            {
                return FormValidation.error(Messages.JobCopyBuilder_JobName_empty());
            }
            if(containsVariable(jobName))
            {
                return FormValidation.ok();
            }
            
            TopLevelItem job = Jenkins.getInstance().getItem(jobName, context, TopLevelItem.class);
            if(job != null)
            {
                // job exists
                if(warnIfExists)
                {
                    return FormValidation.warning(Messages.JobCopyBuilder_JobName_exists());
                }
            }
            else
            {
                // job does not exist
                if(warnIfNotExists)
                {
                    return FormValidation.warning(Messages.JobCopyBuilder_JobName_notExists());
                }
            }
            
            return FormValidation.ok();
        }
        
        /**
         * Validate "From Job Name" field.
         * 
         * @param fromJobName
         * @return
         */
        public FormValidation doCheckFromJobName(@AncestorInPath AbstractProject<?,?> project, @QueryParameter String fromJobName)
        {
            return doCheckJobName(project, fromJobName, false, true);
        }
        
        /**
         * Validate "To Job Name" field.
         * 
         * @param toJobName
         * @param overwrite
         * @return FormValidation object.
         */
        public FormValidation doCheckToJobName(@AncestorInPath AbstractProject<?,?> project, @QueryParameter String toJobName, @QueryParameter boolean overwrite)
        {
            return doCheckJobName(project, toJobName, !overwrite, false);
        }
    }
}

