package com.bluetrainsoftware.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.metadata.ResolutionGroup;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.dependency.AbstractResolveMojo;
import org.apache.maven.plugin.dependency.utils.DependencyStatusSets;
import org.apache.maven.plugin.dependency.utils.DependencyUtil;
import org.apache.maven.plugin.dependency.utils.filters.ResolveFileFilter;
import org.apache.maven.plugin.dependency.utils.markers.SourcesFileMarkerHandler;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.collection.*;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Creates a creates a maven 2 POM for an existing Grails project.
 *
 * @author Richard Vowles
 * @since 1.1
 */
@Mojo(name="release-pom", requiresProject = false, requiresDependencyResolution = ResolutionScope.TEST)
public class ReleasePomMojo extends AbstractResolveMojo {
  @Parameter(required = true, readonly = true, property = "project")
  protected MavenProject project;

  @Component
  private ArtifactFactory artifactFactory;

  @Component
  private ArtifactMetadataSource artifactMetadataSource;

  @Parameter(property = "localRepository")
  private ArtifactRepository localRepository;

  @Parameter(property = "outputFile")
  private File outputFile;

  protected DependencyStatusSets getDependencySets( boolean stopOnFailure )
    throws MojoExecutionException
  {
    // add filters in well known order, least specific to most specific
    FilterArtifacts filter = new FilterArtifacts();

    filter.addFilter( new ProjectTransitivityFilter( project.getDependencyArtifacts(), this.excludeTransitive ) );

    filter.addFilter( new ScopeFilter( DependencyUtil.cleanToBeTokenizedString(this.includeScope),
      DependencyUtil.cleanToBeTokenizedString( this.excludeScope ) ) );

    filter.addFilter( new TypeFilter( DependencyUtil.cleanToBeTokenizedString( this.includeTypes ),
      DependencyUtil.cleanToBeTokenizedString( this.excludeTypes ) ) );

    filter.addFilter( new ClassifierFilter( DependencyUtil.cleanToBeTokenizedString( this.includeClassifiers ),
      DependencyUtil.cleanToBeTokenizedString( this.excludeClassifiers ) ) );

    filter.addFilter( new GroupIdFilter( DependencyUtil.cleanToBeTokenizedString( this.includeGroupIds ),
      DependencyUtil.cleanToBeTokenizedString( this.excludeGroupIds ) ) );

    filter.addFilter( new ArtifactIdFilter( DependencyUtil.cleanToBeTokenizedString( this.includeArtifactIds ),
      DependencyUtil.cleanToBeTokenizedString( this.excludeArtifactIds ) ) );

    // start with all artifacts.
    Set<Artifact> artifacts = project.getArtifacts();

    // perform filtering
    try
    {
      artifacts = filter.filter( artifacts );
    }
    catch ( ArtifactFilterException e )
    {
      throw new MojoExecutionException( e.getMessage(), e );
    }

    // transform artifacts if classifier is set
    DependencyStatusSets status = null;
    if ( StringUtils.isNotEmpty(classifier) )
    {
      status = getClassifierTranslatedDependencies( artifacts, stopOnFailure );
    }
    else
    {
      status = filterMarkedDependencies( artifacts );
    }

    return status;
  }

  private void formatParent(StringBuffer sb) {
    MavenProject parent = project.getParent();

    if (parent != null) {

      sb.append("\t<parent>\n\t\t");
    }

  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    DependencyStatusSets results = this.getDependencySets( false );

    if (results.getUnResolvedDependencies() != null && results.getUnResolvedDependencies().size() > 0) {
      System.out.println("Unable reliably determine dependencies\n" + results.getOutput(true, true));
      throw new MojoFailureException("Unable to reliably determine dependencies");
    }

    ReleaseTemplate releaseTemplate = new ReleaseTemplate(project, results.getResolvedDependencies(), artifactMetadataSource, localRepository);

    System.out.println(releaseTemplate.generateReleasePom());

  }

  private Artifact dependencyToArtifact(final Dependency dep) {
    return this.artifactFactory.createDependencyArtifact(dep.getGroupId(), dep.getArtifactId(), VersionRange.createFromVersion(dep.getVersion()),
      dep.getType(), dep.getClassifier(), dep.getScope());
  }

  @Override
  protected ArtifactsFilter getMarkedArtifactFilter() {
    return new ResolveFileFilter( new SourcesFileMarkerHandler( this.markersDirectory ) );
  }
}
