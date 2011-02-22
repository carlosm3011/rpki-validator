package net.ripe.certification.validator.commands;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import net.ripe.certification.validator.fetchers.CachingCertificateRepositoryObjectFetcher;
import net.ripe.certification.validator.fetchers.CertificateRepositoryObjectFetcher;
import net.ripe.certification.validator.fetchers.NotifyingCertificateRepositoryObjectFetcher;
import net.ripe.certification.validator.fetchers.RsyncCertificateRepositoryObjectFetcher;
import net.ripe.certification.validator.fetchers.ValidatingCertificateRepositoryObjectFetcher;
import net.ripe.certification.validator.output.ObjectFetcherResultLogger;
import net.ripe.certification.validator.output.ValidatedObjectWriter;
import net.ripe.certification.validator.output.ValidatedRoaWriter;
import net.ripe.certification.validator.runtimeproblems.ValidatorIOException;
import net.ripe.certification.validator.summary.ValidationSummaryCollector;
import net.ripe.certification.validator.summary.ValidationSummaryPrinter;
import net.ripe.certification.validator.util.UriToFileMapper;
import net.ripe.commons.certification.rsync.Rsync;
import net.ripe.commons.certification.validation.ValidationResult;
import net.ripe.commons.certification.validation.objectvalidators.CertificateRepositoryObjectValidationContext;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.Validate;


/**
 * Validates a complete repository, recursively, starting with the trust anchors.
 */
public class TopDownCertificateRepositoryValidator {

    static final String BASE_DIRECTORY_NAME = "rta";

    static final String VALIDATED_DIRECTORY_NAME = "validated";

    static final String OLD_VALIDATED_DIRECTORY_NAME = "validated.old";

    static final String UNVALIDATED_DIRECTORY_NAME = "unvalidated";

    private List<CertificateRepositoryObjectValidationContext> trustAnchors;

    private List<URI> prefetchUris = new ArrayList<URI>();

    private File outputDirectory;

    private TopDownWalker topDownWalker;

    private boolean roaExportEnabled;

    private File roaExportFile;

    private ValidatedRoaWriter roaExporterCallBack;

    private CertificateRepositoryObjectFetcher fetcher;

    private ValidationSummaryCollector validationSummaryCollector;

    public TopDownCertificateRepositoryValidator(List<CertificateRepositoryObjectValidationContext> trustAnchors, File outputDirectory) {
        Validate.notNull(trustAnchors);
        Validate.notNull(outputDirectory);
        init(trustAnchors, outputDirectory, false, null);
    }

    public TopDownCertificateRepositoryValidator(List<CertificateRepositoryObjectValidationContext> trustAnchors, File outputDirectory,
            File roaExportFile) {
        Validate.notNull(trustAnchors);
        Validate.notNull(outputDirectory);
        Validate.notNull(roaExportFile);
        init(trustAnchors, outputDirectory, true, roaExportFile);
    }

    private void init(List<CertificateRepositoryObjectValidationContext> trustAnchors, File outputDirectory, boolean roaExportEnabled,
            File roaExportFile) {
        this.trustAnchors = trustAnchors;
        this.outputDirectory = outputDirectory;
        this.roaExportFile = roaExportFile;
        this.roaExportEnabled = roaExportEnabled;
        if (roaExportEnabled) {
            roaExporterCallBack = new ValidatedRoaWriter();
        }
        this.fetcher = createCertificateRepositoryObjectFetcher();
        this.topDownWalker = new TopDownWalker(fetcher);
    }
    
    private CachingCertificateRepositoryObjectFetcher createCertificateRepositoryObjectFetcher() {
        RsyncCertificateRepositoryObjectFetcher rsyncFetcher = new RsyncCertificateRepositoryObjectFetcher(new Rsync(), new UriToFileMapper(getUnvalidatedOutputDirectory()));
        
        ValidatingCertificateRepositoryObjectFetcher validatingFetcher = new ValidatingCertificateRepositoryObjectFetcher(rsyncFetcher);
        
        NotifyingCertificateRepositoryObjectFetcher notifyingFetcher = new NotifyingCertificateRepositoryObjectFetcher(validatingFetcher);
        notifyingFetcher.addCallback(new ObjectFetcherResultLogger());
        notifyingFetcher.addCallback(new ValidatedObjectWriter(new UriToFileMapper(getValidatedOutputDirectory())));
        
        validationSummaryCollector = new ValidationSummaryCollector();
        notifyingFetcher.addCallback(validationSummaryCollector);
        if (roaExportEnabled) {
            notifyingFetcher.addCallback(roaExporterCallBack);
        }
        
        CachingCertificateRepositoryObjectFetcher cachingFetcher = new CachingCertificateRepositoryObjectFetcher(notifyingFetcher);
        
        validatingFetcher.setOuterMostDecorator(cachingFetcher);
        return cachingFetcher;
    }

    public void setPrefetchUris(List<URI> prefetchUris) {
        Validate.notNull(prefetchUris, "prefetch URIs is null");
        this.prefetchUris = prefetchUris;
    }

    public void prepare() {
        File oldValidatedDirectory = new File(new File(outputDirectory, BASE_DIRECTORY_NAME), OLD_VALIDATED_DIRECTORY_NAME);

        try {
            if (oldValidatedDirectory.exists()) {
                FileUtils.deleteDirectory(oldValidatedDirectory);
            }
        } catch (IOException e) {
            throw new ValidatorIOException("Could not delete existing output directory (" + oldValidatedDirectory.getAbsolutePath() + ")", e);
        }

        File validatedDirectory = getValidatedOutputDirectory();
        if (validatedDirectory.exists()) {
            validatedDirectory.renameTo(oldValidatedDirectory);
        }

        validatedDirectory.mkdirs();

        File unvalidatedDirectory = getUnvalidatedOutputDirectory();
        unvalidatedDirectory.mkdirs();
        if (!unvalidatedDirectory.isDirectory()) {
            throw new ValidatorIOException("directory " + unvalidatedDirectory + " could not be created. Is there a file in the way?");
        }
    }

    public void validate() {
        doPrefetching();
        processTrustAnchors();
        
        if (roaExportEnabled) {
            roaExporterCallBack.writeCsvFile(roaExportFile);
        }
        
        printSummary();
    }


    private void doPrefetching() {
        for (URI prefetchUri : prefetchUris) {
            ValidationResult validationResult = new ValidationResult();
            validationResult.push(prefetchUri);
            fetcher.prefetch(prefetchUri, validationResult);
        }
    }

    private void processTrustAnchors() {
        for (CertificateRepositoryObjectValidationContext trustAnchor : trustAnchors) {
            topDownWalker.addTrustAnchor(trustAnchor);
            topDownWalker.execute();
        }
    }
    
    private void printSummary() {
        System.out.print(ValidationSummaryPrinter.getMessage(validationSummaryCollector)); //NOPMD - We want this summary without the usual LOG4J stuff..
    }



    private File getUnvalidatedOutputDirectory() {
        return new File(new File(outputDirectory, BASE_DIRECTORY_NAME), UNVALIDATED_DIRECTORY_NAME);
    }

    private File getValidatedOutputDirectory() {
        return new File(new File(outputDirectory, BASE_DIRECTORY_NAME), VALIDATED_DIRECTORY_NAME);
    }

    // Testing
    void setFetcher(CertificateRepositoryObjectFetcher fetcher) {
        this.fetcher = fetcher;
    }

    // Testing
    CertificateRepositoryObjectFetcher getFetcher() {
        return fetcher;
    }

    // Testing
    void setTopDownWalker(TopDownWalker topDownWalker) {
        this.topDownWalker = topDownWalker;
    }
}
