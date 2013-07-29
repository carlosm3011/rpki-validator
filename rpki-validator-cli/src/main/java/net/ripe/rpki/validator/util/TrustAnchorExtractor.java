/**
 * The BSD License
 *
 * Copyright (c) 2010-2012 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator.util;

import net.ripe.rpki.validator.cli.CommandLineOptions;
import net.ripe.rpki.validator.runtimeproblems.ValidatorIOException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.ripe.rpki.commons.rsync.Rsync;
import net.ripe.rpki.commons.validation.objectvalidators.CertificateRepositoryObjectValidationContext;
import net.ripe.rpki.commons.crypto.x509cert.X509CertificateUtil;
import net.ripe.rpki.commons.crypto.x509cert.X509ResourceCertificate;


public class TrustAnchorExtractor {

    private final Rsync rsync;

    public TrustAnchorExtractor() {
        this(new Rsync());
    }

    public TrustAnchorExtractor(Rsync rsync) {
        this.rsync = rsync;
    }

    public List<CertificateRepositoryObjectValidationContext> extractTAS(CommandLineOptions options) {
        return extractTAS(options.getTrustAnchorFiles(), options.getOutputDir().getAbsolutePath());
    }

    public List<CertificateRepositoryObjectValidationContext> extractTAS(List<TrustAnchorLocator> list, String rootCertificateOutputDir) {
        List<CertificateRepositoryObjectValidationContext> tas = new ArrayList<CertificateRepositoryObjectValidationContext>();
        for (TrustAnchorLocator tal : list) {
            tas.add(extractTA(tal, rootCertificateOutputDir));
        }
        return tas;
    }

    public CertificateRepositoryObjectValidationContext extractTA(TrustAnchorLocator tal, String rootCertificateOutputDir) {
        X509ResourceCertificate cert = getRemoteCertificate(tal, rootCertificateOutputDir);

        verifyTrustAnchor(tal, cert);

        return new CertificateRepositoryObjectValidationContext(tal.getCertificateLocation(), cert);
    }

    private void verifyTrustAnchor(TrustAnchorLocator tal, X509ResourceCertificate resourceCertificate) {
        String encodedSubjectPublicKeyInfo;
        try {
            encodedSubjectPublicKeyInfo = X509CertificateUtil.getEncodedSubjectPublicKeyInfo(resourceCertificate.getCertificate());
        } catch (Exception e) {
            throw new TrustAnchorExtractorException("Problem parsing remote Trust Anchor certificate", e);
        }
        if (!encodedSubjectPublicKeyInfo.equals(tal.getPublicKeyInfo())) {
            throw new TrustAnchorExtractorException("Remote Trust Anchor does not match public key mentioned in TAL");
        }
    }

    private X509ResourceCertificate getRemoteCertificate(TrustAnchorLocator tal, String rootCertificateOutputDir) {
    	String targetDirectoryPath = rootCertificateOutputDir;
        File targetDirectory = new File(targetDirectoryPath);
        if (!targetDirectory.exists()) {
             targetDirectory.mkdirs();
        }
        String dest = targetDirectoryPath + "/" + tal.getFile().getName() + ".cer";
  //      System.out.print("dest: "+dest);
        int exitStatus=1, tamanio=tal.getCertificateLocations().length, i=0;
        System.out.println("MPP%% TAL URIs found: "+tamanio);
        while(exitStatus!=0 && i<tamanio){
	        rsync.reset();
	        String uri=tal.getCertificateLocations()[i].toString();
	        System.out.println("MPP%% Trying repository fetch from: "+uri);
	        rsync.setSource(uri);
	        rsync.setDestination(dest);
	        exitStatus = rsync.execute();
	        if (exitStatus !=0 ) {
	        	System.out.println("MPP%% fetch from URI ["+uri+"] FAILED! Failing over to next URI ");
	        }
	        i++;
        }
        
        switch (exitStatus) {
        case 0:
            return CertificateRepositoryObjectLocalFileHelper.readCertificate(new File(dest));
        default:
            // throw new ValidatorIOException("rsync failed while retrieving remote certificate from '" + tal.getCertificateLocation() + "': exit status: " + exitStatus);
        	throw new ValidatorIOException("MPP%% rsync failed while retrieving remote certificate from all locations exit status: " + exitStatus);        	
        }
    }
}
