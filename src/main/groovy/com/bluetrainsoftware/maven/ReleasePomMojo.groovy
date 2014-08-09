package com.bluetrainsoftware.maven

import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.factory.ArtifactFactory
import org.apache.maven.artifact.metadata.ArtifactMetadataSource
import org.apache.maven.artifact.repository.ArtifactRepository
import org.apache.maven.artifact.resolver.ArtifactCollector
import org.apache.maven.artifact.resolver.ArtifactNotFoundException
import org.apache.maven.artifact.resolver.ArtifactResolutionException
import org.apache.maven.artifact.resolver.ArtifactResolver
import org.apache.maven.artifact.versioning.VersionRange
import org.apache.maven.model.Dependency
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugin.dependency.AbstractResolveMojo
import org.apache.maven.plugin.dependency.utils.DependencyStatusSets
import org.apache.maven.plugin.dependency.utils.DependencyUtil
import org.apache.maven.plugin.dependency.utils.filters.ResolveFileFilter
import org.apache.maven.plugin.dependency.utils.markers.SourcesFileMarkerHandler
import org.apache.maven.plugins.annotations.*
import org.apache.maven.project.MavenProject
import org.apache.maven.project.MavenProjectHelper
import org.apache.maven.shared.artifact.filter.collection.*
import org.apache.maven.shared.dependency.tree.DependencyNode
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException
import org.apache.maven.shared.dependency.tree.traversal.DependencyNodeVisitor
import org.codehaus.plexus.util.StringUtils

import java.io.File
import java.util.*

/**
 * Creates a creates a maven 2 POM for an existing Grails project.
 *
 * @author Richard Vowles
 * @since 1.1
 */
@Mojo(name="release-pom", requiresProject = false, requiresDependencyResolution = ResolutionScope.TEST, defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public class ReleasePomMojo extends AbstractResolveMojo {
  @Parameter(required = true, readonly = true, property = "project")
  protected MavenProject project

  @Component
  private ArtifactFactory artifactFactory

  @Component
  private ArtifactMetadataSource artifactMetadataSource

  @Parameter(property = "localRepository")
  private ArtifactRepository localRepository

  @Parameter(property = "run.outputFile")
  private String outputFile = "released-pom.xml"

  @Parameter(property = "run.outputFilePom")
  private String outputFilePom = "released-pom.xml"

  @Parameter(property = "run.useMaven2")
  private boolean useMaven2 = true

  @Component
  private DependencyTreeBuilder dependencyTreeBuilder

  @Component
  private ArtifactCollector artifactCollector

  @Parameter(property = "project.remoteArtifactRepositories")
  private List<ArtifactRepository> remoteRepositories

  @Component
  private ArtifactResolver artifactResolver

  @Component
  private MavenProjectHelper projectHelper

  protected DependencyStatusSets getDependencySets( boolean stopOnFailure )
    throws MojoExecutionException
  {
    // add filters in well known order, least specific to most specific
    FilterArtifacts filter = new FilterArtifacts()

    filter.addFilter( new ProjectTransitivityFilter( project.getDependencyArtifacts(), this.excludeTransitive ) )

    filter.addFilter( new ScopeFilter( DependencyUtil.cleanToBeTokenizedString(this.includeScope),
      DependencyUtil.cleanToBeTokenizedString( this.excludeScope ) ) )

    filter.addFilter( new TypeFilter( DependencyUtil.cleanToBeTokenizedString( this.includeTypes ),
      DependencyUtil.cleanToBeTokenizedString( this.excludeTypes ) ) )

    filter.addFilter( new ClassifierFilter( DependencyUtil.cleanToBeTokenizedString( this.includeClassifiers ),
      DependencyUtil.cleanToBeTokenizedString( this.excludeClassifiers ) ) )

    filter.addFilter( new GroupIdFilter( DependencyUtil.cleanToBeTokenizedString( this.includeGroupIds ),
      DependencyUtil.cleanToBeTokenizedString( this.excludeGroupIds ) ) )

    filter.addFilter( new ArtifactIdFilter( DependencyUtil.cleanToBeTokenizedString( this.includeArtifactIds ),
      DependencyUtil.cleanToBeTokenizedString( this.excludeArtifactIds ) ) )

    // start with all artifacts.
    Set<Artifact> artifacts = project.getArtifacts()

    // perform filtering
    try
    {
      artifacts = filter.filter( artifacts )
    }
    catch ( ArtifactFilterException e )
    {
      throw new MojoExecutionException( e.getMessage(), e )
    }

    // transform artifacts if classifier is set
    DependencyStatusSets status = null
    if ( StringUtils.isNotEmpty(classifier) )
    {
      status = getClassifierTranslatedDependencies( artifacts, stopOnFailure )
    }
    else
    {
      status = filterMarkedDependencies( artifacts )
    }

    return status
  }

  private Set<Artifact> resolveTreeFromMaven2() {
    final Set<Artifact> resolvedArtifacts = new HashSet<Artifact>()
	  MavenProject releaseProject = project

    try {
      // we have to do this because Aether does not work.
      dependencyTreeBuilder.buildDependencyTree(project, localRepository, artifactFactory,
        artifactMetadataSource, artifactCollector).getRootNode().accept(new DependencyNodeVisitor() {
        @Override
        public boolean visit(DependencyNode dependencyNode) {
          Artifact artifact = dependencyNode.artifact

          if (dependencyNode.state != DependencyNode.INCLUDED)
            return true

	        if (artifact.artifactId == releaseProject.artifactId && artifact.groupId == releaseProject.groupId) {
		        return true
	        }

          try {
            artifactResolver.resolve(artifact, remoteRepositories, localRepository)
          } catch (ArtifactResolutionException e) {
            throw new RuntimeException(e)
          } catch (ArtifactNotFoundException e) {
            throw new RuntimeException(e)
          }

          resolvedArtifacts.add(artifact)
          return true
        }

        @Override
        public boolean endVisit(DependencyNode dependencyNode) {
          return true
        }
      })
    } catch (DependencyTreeBuilderException e) {
      throw new RuntimeException(e)
    }

    return resolvedArtifacts
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    getLog().info("Generating output file for resolved dependencies into " + outputFile)

    Set<Artifact> resolvedArtifacts

    if (useMaven2) {
      resolvedArtifacts = resolveTreeFromMaven2()
    } else {
      DependencyStatusSets results = this.getDependencySets( false )

      if (results.getUnResolvedDependencies() != null && results.getUnResolvedDependencies().size() > 0) {
        System.out.println("Unable reliably determine dependencies\n" + results.getOutput(true, true))
        throw new MojoFailureException("Unable to reliably determine dependencies")
      }

      resolvedArtifacts = results.getResolvedDependencies()
    }

    String finalOutputFile = "pom".equals(project.getPackaging()) ? outputFilePom : outputFile

    File fileOutputFile = new File(finalOutputFile)
    File parentDir = fileOutputFile.getParentFile()

    if (parentDir != null && !parentDir.exists()) {
      parentDir.mkdirs()
    }

    ReleaseTemplate releaseTemplate = new ReleaseTemplate(project, resolvedArtifacts, artifactMetadataSource, localRepository, finalOutputFile)

    releaseTemplate.generateReleasePom()

    projectHelper.attachArtifact(project, "pom", "release-pom", new File(finalOutputFile))
  }

  private Artifact dependencyToArtifact(final Dependency dep) {
    return this.artifactFactory.createDependencyArtifact(dep.getGroupId(), dep.getArtifactId(), VersionRange.createFromVersion(dep.getVersion()),
      dep.getType(), dep.getClassifier(), dep.getScope())
  }

  @Override
  protected ArtifactsFilter getMarkedArtifactFilter() {
    return new ResolveFileFilter( new SourcesFileMarkerHandler( this.markersDirectory ) )
  }
}
