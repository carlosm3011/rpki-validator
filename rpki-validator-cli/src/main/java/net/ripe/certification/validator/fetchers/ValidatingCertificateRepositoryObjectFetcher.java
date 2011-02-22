package net.ripe.certification.validator.fetchers;

import java.io.File;
import java.net.URI;

import net.ripe.commons.certification.CertificateRepositoryObject;
import net.ripe.commons.certification.cms.manifest.ManifestCms;
import net.ripe.commons.certification.crl.CrlLocator;
import net.ripe.commons.certification.crl.X509Crl;
import net.ripe.commons.certification.validation.ValidationResult;
import net.ripe.commons.certification.validation.ValidationString;
import net.ripe.commons.certification.validation.objectvalidators.CertificateRepositoryObjectValidationContext;
import net.ripe.utils.Specification;

import org.apache.commons.lang.Validate;


public class ValidatingCertificateRepositoryObjectFetcher implements CertificateRepositoryObjectFetcher {

    private final CertificateRepositoryObjectFetcher fetcher;

    private CertificateRepositoryObjectFetcher outerMostDecorator;

    /**
     * A validating CROFetcher. All objects retrieved are being validated. Invalid objects result in
     * null values being returned instead. Note that validation requires a CrlLocator. Because other
     * decorating CROFetchers are likely to be used (notifying, caching) a setter is provided to
     * allow for the outermost decorator to be used for the CRL retrieval.
     */
    public ValidatingCertificateRepositoryObjectFetcher(CertificateRepositoryObjectFetcher fetcher) {
        this.fetcher = fetcher;
        this.outerMostDecorator = this;
    }

    /**
     * Set the outermost decorator which will be used as the CrlLocator for validation
     */
    public void setOuterMostDecorator(CertificateRepositoryObjectFetcher outerMostDecorator) {
        this.outerMostDecorator = outerMostDecorator;
    }

    @Override
    public X509Crl getCrl(URI uri, CertificateRepositoryObjectValidationContext context, ValidationResult result) {
        Validate.notNull(context);
        Validate.notNull(result);
        Validate.notNull(uri);

        /*
         * Three step process:
         * - Get the CRL and validate it ignoring hash for content
         * - Get its manifest and validate the manifest based on this CRL
         * - Re-validate the CRL for its hash
         */

        // 1: Get the CRL without hash validation
        X509Crl crl = fetcher.getCrl(uri, context, result);
        if (crl == null) {
            return null;
        }
        crl = (X509Crl) processCertificateRepositoryObject(uri, context, result, crl);

        // 2: Get the manifest and validate it based on this CRL
        ManifestCms manifest = getManifestValidatedForCrl(uri, context, result, crl);
        result.isTrue(manifest != null, ValidationString.CRL_MANIFEST_VALID);
        if (manifest == null) {
            return null;
        }

        // 3: Re-validate the hash for this CRL
        checkHashValueForCrl(uri, result, crl, manifest);
        if (result.hasFailureForCurrentLocation()) {
            return null;
        }

        return crl;
    }


    @Override
    public ManifestCms getManifest(URI uri, CertificateRepositoryObjectValidationContext context, ValidationResult result) {
        Validate.notNull(context);
        Validate.notNull(result);

        ManifestCms manifestCms = fetcher.getManifest(uri, context, result);
        return (ManifestCms) processCertificateRepositoryObject(uri, context, result, manifestCms);
    }

    @Override
    public CertificateRepositoryObject getObject(URI uri, CertificateRepositoryObjectValidationContext context,
            Specification<byte[]> fileContentSpecification, ValidationResult result) {
        Validate.notNull(context);
        Validate.notNull(result);

        CertificateRepositoryObject certificateRepositoryObject = fetcher.getObject(uri, context, fileContentSpecification, result);
        return processCertificateRepositoryObject(uri, context, result, certificateRepositoryObject);
    }

    @Override
    public void prefetch(URI uri, ValidationResult result) {
        fetcher.prefetch(uri, result);
    }

    private CertificateRepositoryObject processCertificateRepositoryObject(URI uri, CertificateRepositoryObjectValidationContext context,
            ValidationResult result, CertificateRepositoryObject certificateRepositoryObject) {
        if (certificateRepositoryObject == null) {
            return null;
        }
        certificateRepositoryObject.validate(uri.toString(), context, outerMostDecorator, result);
        if (result.hasFailureForCurrentLocation()) {
            return null;
        }
        return certificateRepositoryObject;
    }


    private void checkHashValueForCrl(URI uri, ValidationResult result, X509Crl crl, ManifestCms manifest) {
        String crlFileName = new File(uri.getRawPath()).getName();

        // FIXME: is this really the right way to go with error locations?
        //        this way the manifest check error does end up with the CRL which I believe is right..
        result.push(uri.toString());
        result.isTrue(manifest.containsFile(crlFileName), ValidationString.VALIDATOR_MANIFEST_DOES_NOT_CONTAIN_FILE, crlFileName);
        if (result.hasFailureForCurrentLocation()) {
            return;
        }
        result.isTrue(manifest.verifyFileContents(crlFileName, crl.getEncoded()), ValidationString.VALIDATOR_FILE_CONTENT);
    }


    private ManifestCms getManifestValidatedForCrl(URI crlUri, CertificateRepositoryObjectValidationContext context, ValidationResult result, X509Crl crl) {
        String savedCurrentLocation = result.getCurrentLocation();
        result.push(context.getManifestURI());
        try {
            ManifestCms manifest = fetcher.getManifest(context.getManifestURI(), context, result);
            if (manifest == null) {
                return null;
            }

            final X509Crl crlForCrlLocator = crl;
            final URI expectedURIforCrlLocator = crlUri;

            manifest.validate(context.getManifestURI().toString(), context, new CrlLocator() {

                @Override
                public X509Crl getCrl(URI uri, CertificateRepositoryObjectValidationContext context, ValidationResult result) {
                    Validate.isTrue(uri.equals(expectedURIforCrlLocator));
                    return crlForCrlLocator;
                }

            }, result);
            if (result.hasFailureForCurrentLocation()) {
                return null;
            }
            return manifest;
        } finally {
            result.push(savedCurrentLocation);
        }
    }

}
