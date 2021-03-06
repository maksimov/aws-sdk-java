/*
 * Copyright 2013-2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.services.s3.internal.crypto;

import static com.amazonaws.services.s3.AmazonS3EncryptionClient.USER_AGENT;
import static com.amazonaws.services.s3.model.CryptoStorageMode.InstructionFile;
import static com.amazonaws.services.s3.model.CryptoStorageMode.ObjectMetadata;
import static com.amazonaws.services.s3.model.InstructionFileId.DEFAULT_INSTRUCTION_FILE_SUFFIX;
import static com.amazonaws.services.s3.model.InstructionFileId.DOT;
import static com.amazonaws.util.IOUtils.closeQuietly;
import static com.amazonaws.util.LengthCheckInputStream.EXCLUDE_SKIPPED_BYTES;
import static com.amazonaws.util.StringUtils.UTF8;
import static com.amazonaws.util.Throwables.failure;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.internal.SdkFilterInputStream;
import com.amazonaws.services.s3.Headers;
import com.amazonaws.services.s3.internal.InputSubstream;
import com.amazonaws.services.s3.internal.Mimetypes;
import com.amazonaws.services.s3.internal.RepeatableFileInputStream;
import com.amazonaws.services.s3.internal.S3Direct;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadResult;
import com.amazonaws.services.s3.model.CopyPartRequest;
import com.amazonaws.services.s3.model.CopyPartResult;
import com.amazonaws.services.s3.model.CryptoConfiguration;
import com.amazonaws.services.s3.model.CryptoMode;
import com.amazonaws.services.s3.model.EncryptionMaterials;
import com.amazonaws.services.s3.model.EncryptionMaterialsFactory;
import com.amazonaws.services.s3.model.EncryptionMaterialsProvider;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.InstructionFileId;
import com.amazonaws.services.s3.model.MaterialsDescriptionProvider;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutInstructionFileRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectId;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.amazonaws.util.LengthCheckInputStream;
import com.amazonaws.util.json.Jackson;

/**
 * Common implementation for different S3 cryptographic modules.
 */
public abstract class S3CryptoModuleBase<T extends MultipartUploadCryptoContext>
        extends S3CryptoModule<T> {
    private static final boolean IS_MULTI_PART = true;
    protected static final int DEFAULT_BUFFER_SIZE = 1024*2;    // 2K
    protected final EncryptionMaterialsProvider kekMaterialsProvider;
    protected final Log log = LogFactory.getLog(getClass());
    protected final S3CryptoScheme cryptoScheme;
    protected final ContentCryptoScheme contentCryptoScheme;
    /** A read-only copy of the crypto configuration. */
    protected final CryptoConfiguration cryptoConfig;

    /** Map of data about in progress encrypted multipart uploads. */
    protected final  Map<String, T> multipartUploadContexts =
        Collections.synchronizedMap(new HashMap<String,T>());
    protected final S3Direct s3;

    /**
     * @param cryptoConfig a read-only copy of the crypto configuration.
     */
    protected S3CryptoModuleBase(S3Direct s3,
            AWSCredentialsProvider credentialsProvider,
            EncryptionMaterialsProvider kekMaterialsProvider,
            CryptoConfiguration cryptoConfig) {
        if (!cryptoConfig.isReadOnly())
            throw new IllegalArgumentException("The cryto configuration parameter is required to be read-only");
        this.kekMaterialsProvider = kekMaterialsProvider;
        this.s3 = s3;
        this.cryptoConfig = cryptoConfig;
        this.cryptoScheme = S3CryptoScheme.from(cryptoConfig.getCryptoMode());
        this.contentCryptoScheme = cryptoScheme.getContentCryptoScheme();
    }

    /**
     * Returns the length of the ciphertext computed from the length of the
     * plaintext.
     * 
     * @param plaintextLength
     *            a non-negative number
     * @return a non-negative number
     */
    protected abstract long ciphertextLength(long plaintextLength);

    //////////////////////// Common Implementation ////////////////////////
    @Override
    public PutObjectResult putObjectSecurely(PutObjectRequest putObjectRequest) {
        appendUserAgent(putObjectRequest, USER_AGENT);

        if (cryptoConfig.getStorageMode() == InstructionFile) {
            return putObjectUsingInstructionFile(putObjectRequest);
        } else {
            return putObjectUsingMetadata(putObjectRequest);
        }
    }

    private PutObjectResult putObjectUsingMetadata(PutObjectRequest req) {
        ContentCryptoMaterial cekMaterial = createContentCryptoMaterial(req);
        // Wraps the object data with a cipher input stream
        PutObjectRequest wrappedReq = wrapWithCipher(req, cekMaterial);
        // Update the metadata
        req.setMetadata(updateMetadataWithContentCryptoMaterial(
                req.getMetadata(), req.getFile(), cekMaterial));
        // Put the encrypted object into S3
        return s3.putObject(wrappedReq);
    }

    /**
     * Puts an encrypted object into S3, and puts an instruction file into S3.
     * Encryption info is stored in the instruction file.
     * 
     * @param putObjectRequest
     *            The request object containing all the parameters to upload a
     *            new object to Amazon S3.
     * @return A {@link PutObjectResult} object containing the information
     *         returned by Amazon S3 for the new, created object.
     */
    private PutObjectResult putObjectUsingInstructionFile(
            PutObjectRequest putObjectRequest) {
        PutObjectRequest putInstFileRequest = putObjectRequest.clone();
        putInstFileRequest.setKey(putInstFileRequest.getKey() + DOT
                + DEFAULT_INSTRUCTION_FILE_SUFFIX);
        // Create instruction
        ContentCryptoMaterial cekMaterial = createContentCryptoMaterial(putObjectRequest);
        // Wraps the object data with a cipher input stream; note the metadata
        // is mutated as a side effect.
        PutObjectRequest req = wrapWithCipher(putObjectRequest, cekMaterial);
        // Put the encrypted object into S3
        PutObjectResult result = s3.putObject(req);
        // Put the instruction file into S3
        s3.putObject(updateInstructionPutRequest(putInstFileRequest,
                cekMaterial));
        // Return the result of the encrypted object PUT.
        return result;
    }

    @Override
    public final void abortMultipartUploadSecurely(AbortMultipartUploadRequest req) {
        s3.abortMultipartUpload(req);
        multipartUploadContexts.remove(req.getUploadId());
    }

    @Override
    public final CopyPartResult copyPartSecurely(CopyPartRequest copyPartRequest) {
        String uploadId = copyPartRequest.getUploadId();
        T uploadContext = multipartUploadContexts.get(uploadId);
        CopyPartResult result = s3.copyPart(copyPartRequest);

        if (!uploadContext.hasFinalPartBeenSeen()) {
            uploadContext.setHasFinalPartBeenSeen(true);
        }
        return result;
    }

    abstract T newUploadContext(InitiateMultipartUploadRequest req,
            ContentCryptoMaterial cekMaterial);

    @Override
    public InitiateMultipartUploadResult initiateMultipartUploadSecurely(
            InitiateMultipartUploadRequest req) {
        appendUserAgent(req, USER_AGENT);
        // Generate a one-time use symmetric key and initialize a cipher to
        // encrypt object data
        ContentCryptoMaterial cekMaterial = createContentCryptoMaterial(req);
        if (cryptoConfig.getStorageMode() == ObjectMetadata) {
            ObjectMetadata metadata = req.getObjectMetadata();
            if (metadata == null)
                metadata = new ObjectMetadata();
            // Store encryption info in metadata
            req.setObjectMetadata(updateMetadataWithContentCryptoMaterial(
                    metadata, null, cekMaterial));
        }
        InitiateMultipartUploadResult result = s3.initiateMultipartUpload(req);
        T uploadContext = newUploadContext(req, cekMaterial);
        if (req instanceof MaterialsDescriptionProvider) {
            MaterialsDescriptionProvider p = (MaterialsDescriptionProvider) req;
            uploadContext.setMaterialsDescription(p.getMaterialsDescription());
        }
        multipartUploadContexts.put(result.getUploadId(), uploadContext);
        return result;
    }

    //// specific crypto module behavior for uploading parts.
    abstract CipherLite cipherLiteForNextPart(T uploadContext);
    abstract long computeLastPartSize(UploadPartRequest req);
    abstract <I extends CipherLiteInputStream> SdkFilterInputStream wrapForMultipart(
            I is, long partSize);
    abstract void updateUploadContext(T uploadContext, SdkFilterInputStream is);
    /**
     * {@inheritDoc}
     * 
     * <p>
     * <b>NOTE:</b> Because the encryption process requires context from
     * previous blocks, parts uploaded with the AmazonS3EncryptionClient (as
     * opposed to the normal AmazonS3Client) must be uploaded serially, and in
     * order. Otherwise, the previous encryption context isn't available to use
     * when encrypting the current part.
     */
    @Override
    public UploadPartResult uploadPartSecurely(UploadPartRequest req) {
        appendUserAgent(req, USER_AGENT);
        final int blockSize = contentCryptoScheme.getBlockSizeInBytes();
        final boolean isLastPart = req.isLastPart();
        final String uploadId = req.getUploadId();
        final long partSize = req.getPartSize();
        final boolean partSizeMultipleOfCipherBlockSize = 0 == (partSize % blockSize);
        if (!isLastPart && !partSizeMultipleOfCipherBlockSize) {
            throw new AmazonClientException(
                "Invalid part size: part sizes for encrypted multipart uploads must be multiples "
                    + "of the cipher block size ("
                    + blockSize
                    + ") with the exception of the last part.");
        }
        final T uploadContext = multipartUploadContexts.get(uploadId);
        if (uploadContext == null) {
            throw new AmazonClientException(
                "No client-side information available on upload ID " + uploadId);
        }
        final SdkFilterInputStream is;
        final UploadPartResult result;
        // Checks the parts are uploaded in series
        uploadContext.beginPartUpload(req.getPartNumber());
        try {
            CipherLite cipherLite = cipherLiteForNextPart(uploadContext);
            is = wrapForMultipart(
                    newMultipartS3CipherInputStream(req, cipherLite), partSize);
            req.setInputStream(is);
            // Treat all encryption requests as input stream upload requests,
            // not as file upload requests.
            req.setFile(null);
            req.setFileOffset(0);
            // The last part of the multipart upload will contain an extra
            // 16-byte mac
            if (isLastPart) {
                // We only change the size of the last part
                long lastPartSize = computeLastPartSize(req);
                if (lastPartSize > -1)
                    req.setPartSize(lastPartSize);
                if (uploadContext.hasFinalPartBeenSeen()) {
                    throw new AmazonClientException(
                            "This part was specified as the last part in a multipart upload, but a previous part was already marked as the last part.  "
                                    + "Only the last part of the upload should be marked as the last part.");
                }
            }

            result = s3.uploadPart(req);
        } finally {
            uploadContext.endPartUpload();
        }
        if (isLastPart)
            uploadContext.setHasFinalPartBeenSeen(true);
        updateUploadContext(uploadContext, is);
        return result;
    }

    protected final CipherLiteInputStream newMultipartS3CipherInputStream(
            UploadPartRequest req, CipherLite cipherLite) {
        try {
            InputStream is = req.getInputStream();
            if (req.getFile() != null) {
                is = new InputSubstream(
                    new RepeatableFileInputStream(
                        req.getFile()),
                        req.getFileOffset(), 
                        req.getPartSize(),
                        req.isLastPart());
            }
            if (cipherLite.markSupported()) {
                return new CipherLiteInputStream(is, cipherLite,
                    DEFAULT_BUFFER_SIZE,
                    IS_MULTI_PART
                );
            } else {
                return new RenewableCipherLiteInputStream(is, cipherLite,
                        DEFAULT_BUFFER_SIZE,
                        IS_MULTI_PART
                    );
            }
        } catch (Exception e) {
            throw failure(e,"Unable to create cipher input stream: ");
        }
    }

    @Override
    public CompleteMultipartUploadResult completeMultipartUploadSecurely(
            CompleteMultipartUploadRequest req) {
        appendUserAgent(req, USER_AGENT);
        String uploadId = req.getUploadId();
        T uploadContext = multipartUploadContexts.get(uploadId);

        if (uploadContext.hasFinalPartBeenSeen() == false) {
            throw new AmazonClientException("Unable to complete an encrypted multipart upload without being told which part was the last.  " +
                    "Without knowing which part was the last, the encrypted data in Amazon S3 is incomplete and corrupt.");
        }

        CompleteMultipartUploadResult result = s3.completeMultipartUpload(req);

        // In InstructionFile mode, we want to write the instruction file only
        // after the whole upload has completed correctly.
        if (cryptoConfig.getStorageMode() == InstructionFile) {
            // Put the instruction file into S3
            s3.putObject(createInstructionPutRequest(
                    uploadContext.getBucketName(),
                    uploadContext.getKey(),
                    uploadContext.getContentCryptoMaterial()));
        }
        multipartUploadContexts.remove(uploadId);
        return result;
    }

    protected final ObjectMetadata updateMetadataWithContentCryptoMaterial(
            ObjectMetadata metadata, File file, ContentCryptoMaterial instruction) {
        if (metadata == null) 
            metadata = new ObjectMetadata();
        if (file != null) {
            Mimetypes mimetypes = Mimetypes.getInstance();
            metadata.setContentType(mimetypes.getMimetype(file));
        }
        return instruction.toObjectMetadata(metadata, cryptoConfig.getCryptoMode());
    }
    
    protected final ContentCryptoMaterial createContentCryptoMaterial(
            AmazonWebServiceRequest req) {
        if (req instanceof EncryptionMaterialsFactory) {
            // per request level encryption materials
            EncryptionMaterialsFactory f = (EncryptionMaterialsFactory)req;
            final EncryptionMaterials materials = f.getEncryptionMaterials();
            if (materials != null) {
                buildContentCryptoMaterial(materials,
                        cryptoConfig.getCryptoProvider());
            }
        }
        if (req instanceof MaterialsDescriptionProvider) {
            // per request level material description
            MaterialsDescriptionProvider mdp = (MaterialsDescriptionProvider) req;
            return newContentCryptoMaterial(this.kekMaterialsProvider,
                    mdp.getMaterialsDescription(),
                    cryptoConfig.getCryptoProvider());
        }
        // per s3 client level encryption materails 
        return newContentCryptoMaterial(this.kekMaterialsProvider,
                cryptoConfig.getCryptoProvider());
    }

    /**
     * Generates and returns the content encryption material with the given kek
     * material, material description and security providers.
     */
    private ContentCryptoMaterial newContentCryptoMaterial(
            EncryptionMaterialsProvider kekMaterialProvider,
            Map<String, String> materialsDescription, Provider provider) {
        EncryptionMaterials kekMaterials = 
            kekMaterialProvider.getEncryptionMaterials(materialsDescription);
        return buildContentCryptoMaterial(kekMaterials, provider);
    }

    /**
     * Generates and returns the content encryption material with the given kek
     * material and security providers.
     */
    private ContentCryptoMaterial newContentCryptoMaterial(
            EncryptionMaterialsProvider kekMaterialProvider,
            Provider provider) {
        EncryptionMaterials kekMaterials = kekMaterialProvider.getEncryptionMaterials();
        if (kekMaterials == null)
            throw new AmazonClientException("No material available from the encryption material provider");
        return buildContentCryptoMaterial(kekMaterials, provider);
    }
    
    private ContentCryptoMaterial buildContentCryptoMaterial(
            EncryptionMaterials kekMaterials, Provider provider) {
        // Generate a one-time use symmetric key and initialize a cipher to encrypt object data
        SecretKey cek = generateCEK();
        // Randomly generate the IV
        byte[] iv = new byte[contentCryptoScheme.getIVLengthInBytes()];
        cryptoScheme.getSecureRandom().nextBytes(iv);
        return ContentCryptoMaterial.create(
            cek, iv, kekMaterials, cryptoScheme, provider);
    }

    protected final SecretKey generateCEK() {
        KeyGenerator generator;
        try {
            generator = KeyGenerator.getInstance(contentCryptoScheme
                    .getKeyGeneratorAlgorithm());
            generator.init(contentCryptoScheme.getKeyLengthInBits(),
                    cryptoScheme.getSecureRandom());
            return generator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new AmazonClientException(
                    "Unable to generate envelope symmetric key:"
                            + e.getMessage(), e);
        }
    }

    /**
     * Returns a request that has the content as input stream wrapped with a
     * cipher, and configured with some meta data and user metadata.
     */
    protected final PutObjectRequest wrapWithCipher(
            PutObjectRequest request, ContentCryptoMaterial cekMaterial) {
        // Create a new metadata object if there is no metadata already.
        ObjectMetadata metadata = request.getMetadata();
        if (metadata == null) {
            metadata = new ObjectMetadata();
        }

        // Record the original Content MD5, if present, for the unencrypted data
        if (metadata.getContentMD5() != null) {
            metadata.addUserMetadata(Headers.UNENCRYPTED_CONTENT_MD5,
                    metadata.getContentMD5());
        }

        // Removes the original content MD5 if present from the meta data.
        metadata.setContentMD5(null);

        // Record the original, unencrypted content-length so it can be accessed
        // later
        final long plaintextLength = plaintextLength(request, metadata);
        if (plaintextLength >= 0) {
            metadata.addUserMetadata(Headers.UNENCRYPTED_CONTENT_LENGTH,
                    Long.toString(plaintextLength));
            // Put the ciphertext length in the metadata
            metadata.setContentLength(ciphertextLength(plaintextLength));
        }
        request.setMetadata(metadata);
        request.setInputStream(newS3CipherLiteInputStream(
            request, cekMaterial, plaintextLength));
        // Treat all encryption requests as input stream upload requests, not as
        // file upload requests.
        request.setFile(null);
        return request;
    }

    private CipherLiteInputStream newS3CipherLiteInputStream(
            PutObjectRequest req, ContentCryptoMaterial cekMaterial,
            long plaintextLength) {
        try {
            InputStream is = req.getInputStream();
            if (req.getFile() != null)
                is = new RepeatableFileInputStream(req.getFile());
            if (plaintextLength > -1) {
                // S3 allows a single PUT to be no more than 5GB, which
                // therefore won't exceed the maximum length that can be
                // encrypted either using any cipher such as CBC or GCM.
                
                // This ensures the plain-text read from the underlying data
                // stream has the same length as the expected total.
                is = new LengthCheckInputStream(is, plaintextLength,
                        EXCLUDE_SKIPPED_BYTES);
            }
            final CipherLite cipherLite = cekMaterial.getCipherLite();
            
            if (cipherLite.markSupported()) {
                return new CipherLiteInputStream(is, cipherLite,
                        DEFAULT_BUFFER_SIZE);
            } else {
                return new RenewableCipherLiteInputStream(is, cipherLite,
                        DEFAULT_BUFFER_SIZE);
            }
        } catch (Exception e) {
            throw failure(e, "Unable to create cipher input stream");
        }
    }

    /**
     * Returns the plaintext length from the request and metadata; or -1 if
     * unknown.
     */
    protected final long plaintextLength(PutObjectRequest request,
            ObjectMetadata metadata) {
        if (request.getFile() != null) {
            return request.getFile().length();
        } else if (request.getInputStream() != null
                && metadata.getRawMetadataValue(Headers.CONTENT_LENGTH) != null) {
            return metadata.getContentLength();
        }
        return -1;
    }

    public final S3CryptoScheme getS3CryptoScheme() {
        return cryptoScheme;
    }

    /**
     * Updates put request to store the specified instruction object in S3.
     *
     * @param req
     *      The put request for the instruction file to be stored in S3.
     * @param cekMaterial
     *      The instruction object to be stored in S3.
     * @return
     *      A put request to store the specified instruction object in S3.
     */
    protected final PutObjectRequest updateInstructionPutRequest(
            PutObjectRequest req, ContentCryptoMaterial cekMaterial) {
        byte[] bytes =  cekMaterial.toJsonString(cryptoConfig.getCryptoMode())
                                   .getBytes(UTF8);
        InputStream is = new ByteArrayInputStream(bytes);
        ObjectMetadata metadata = req.getMetadata();
        if (metadata == null) {
            metadata = new ObjectMetadata();
            req.setMetadata(metadata);
        }
        // Set the content-length of the upload
        metadata.setContentLength(bytes.length);
        // Set the crypto instruction file header
        metadata.addUserMetadata(Headers.CRYPTO_INSTRUCTION_FILE, "");
        // Update the instruction request
        req.setMetadata(metadata);
        req.setInputStream(is);
        req.setFile(null);
        return req;
    }

    protected final PutObjectRequest createInstructionPutRequest(
            String bucketName, String key, ContentCryptoMaterial cekMaterial) {
        byte[] bytes = cekMaterial.toJsonString(cryptoConfig.getCryptoMode())
                                  .getBytes(UTF8);
        InputStream is = new ByteArrayInputStream(bytes);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.addUserMetadata(Headers.CRYPTO_INSTRUCTION_FILE, "");
        InstructionFileId ifileId = new S3ObjectId(bucketName, key)
                .instructionFileId();
        return new PutObjectRequest(ifileId.getBucket(), ifileId.getKey(),
            is, metadata);
    }

    /**
     * Appends a user agent to the request's USER_AGENT client marker.
     * This method is intended only for internal use by the AWS SDK. 
     */
    final <X extends AmazonWebServiceRequest> X appendUserAgent(
            X request, String userAgent) {
        request.getRequestClientOptions().appendUserAgent(userAgent);
        return request;
    }

    /**
     * Checks if the the crypto scheme used in the given content crypto material
     * is allowed to be used in this crypto module. Default is no-op. Subclass
     * may override.
     * 
     * @throws SecurityException
     *             if the crypto scheme used in the given content crypto
     *             material is not allowed in this crypto module.
     */
    protected void securityCheck(ContentCryptoMaterial cekMaterial,
            S3ObjectWrapper retrieved) {
    }

    /**
     * Retrieves an instruction file from S3; or null if no instruction file is
     * found.
     * 
     * @param s3ObjectId
     *            the S3 object id (not the instruction file id)
     * @param instFileSuffix
     *            suffix of the instruction file to be retrieved; or null to use
     *            the default suffix.
     * @return an instruction file, or null if no instruction file is found.
     */
    final S3ObjectWrapper fetchInstructionFile(S3ObjectId s3ObjectId,
            String instFileSuffix) {
        try {
            S3Object o = s3.getObject(
                createInstructionGetRequest(s3ObjectId, instFileSuffix));
            return o == null ? null : new S3ObjectWrapper(o, s3ObjectId);
        } catch (AmazonServiceException e) {
            // If no instruction file is found, log a debug message, and return
            // null.
            if (log.isDebugEnabled()) {
                log.debug("Unable to retrieve instruction file : "
                        + e.getMessage());
            }
            return null;
        }
    }

    @Override
    public final PutObjectResult putInstructionFileSecurely(
            PutInstructionFileRequest req) {
        final S3ObjectId id = req.getS3ObjectId();
        final GetObjectRequest getreq = new GetObjectRequest(id);
        appendUserAgent(getreq, USER_AGENT);
        // Get the object from S3
        S3Object retrieved = s3.getObject(getreq);
        if (retrieved == null) {
            throw new IllegalArgumentException(
                    "The specified S3 object (" + id + ") doesn't exist.");
        }
        S3ObjectWrapper wrapped = new S3ObjectWrapper(retrieved, id);
        try {
            final ContentCryptoMaterial origCCM = contentCryptoMaterialOf(wrapped);
            if (ContentCryptoScheme.AES_GCM.equals(origCCM.getContentCryptoScheme())
            &&  cryptoConfig.getCryptoMode() == CryptoMode.EncryptionOnly) {
                throw new SecurityException(
                    "Lowering the protection of encryption material is not allowed");
            }
            securityCheck(origCCM, wrapped);
            // Re-ecnrypt the CEK in a new content crypto material
            final EncryptionMaterials newKEK = req.getEncryptionMaterials();
            final ContentCryptoMaterial newCCM;
            if (newKEK == null) {
                newCCM = origCCM.recreate(req.getMaterialsDescription(),
                        this.kekMaterialsProvider,
                        cryptoScheme,
                        cryptoConfig.getCryptoProvider());
            } else {
                newCCM = origCCM.recreate(newKEK,
                        this.kekMaterialsProvider,
                        cryptoScheme,
                        cryptoConfig.getCryptoProvider());
            }
            PutObjectRequest putInstFileRequest = req.createPutObjectRequest(retrieved);
            // Put the new instruction file into S3
            return s3.putObject(updateInstructionPutRequest(putInstFileRequest, newCCM));
        } catch (RuntimeException ex) {
            // If we're unable to set up the decryption, make sure we close the
            // HTTP connection
            closeQuietly(retrieved, log);
            throw ex;
        } catch (Error error) {
            closeQuietly(retrieved, log);
            throw error;
        }
    }

    /**
     * Returns the content crypto material of an existing S3 object.
     * 
     * @param s3w
     *            an existing S3 object (wrapper)
     * @param s3objectId
     *            the object id used to retrieve the existing S3 object
     * 
     * @return a non-null content crypto material.
     */
    private ContentCryptoMaterial contentCryptoMaterialOf(S3ObjectWrapper s3w) {
        // Check if encryption info is in object metadata
        if (s3w.hasEncryptionInfo()) {
            return ContentCryptoMaterial
                .fromObjectMetadata(s3w.getObjectMetadata(),
                    kekMaterialsProvider,
                    cryptoConfig.getCryptoProvider(),
                    false   // existing CEK not necessarily key-wrapped
                );
        }
        S3ObjectWrapper orig_ifile = 
            fetchInstructionFile(s3w.getS3ObjectId(), null);
        if (orig_ifile == null) {
            throw new IllegalArgumentException(
                "S3 object is not encrypted: " + s3w);
        }
        if (!orig_ifile.isInstructionFile()) {
            throw new AmazonClientException(
                "Invalid instruction file for S3 object: " + s3w);
        }
        String json = orig_ifile.toJsonString();
        @SuppressWarnings("unchecked")
        Map<String, String> instruction = Collections.unmodifiableMap(
                Jackson.fromJsonString(json, Map.class));
        return ContentCryptoMaterial.fromInstructionFile(
            instruction,
            kekMaterialsProvider,
            cryptoConfig.getCryptoProvider(),
            false   // existing CEK not necessarily key-wrapped
        );
    }

    /**
     * Creates a get object request for an instruction file using
     * the default instruction file suffix.
     *
     * @param id
     *      an S3 object id (not the instruction file id)
     * @return
     *      A get request to retrieve an instruction file from S3.
     */
    final GetObjectRequest createInstructionGetRequest(S3ObjectId id) {
        return createInstructionGetRequest(id, null);
    }

    /**
     * Creates and return a get object request for an instruction file.
     * 
     * @param s3objectId
     *      an S3 object id (not the instruction file id)
     * @param instFileSuffix
     *            suffix of the specific instruction file to be used, or null if
     *            the default instruction file is to be used.
     */
    final GetObjectRequest createInstructionGetRequest(
            S3ObjectId s3objectId, String instFileSuffix) {
        return new GetObjectRequest(
                s3objectId.instructionFileId(instFileSuffix));
    }

    final long[] getAdjustedCryptoRange(long[] range) {
        // If range is invalid, then return null.
        if (range == null || range[0] > range[1]) {
            return null;
        }
        long[] adjustedCryptoRange = new long[2];
        adjustedCryptoRange[0] = getCipherBlockLowerBound(range[0]);
        adjustedCryptoRange[1] = getCipherBlockUpperBound(range[1]);
        return adjustedCryptoRange;
    }
    private static long getCipherBlockLowerBound(long leftmostBytePosition) {
        long cipherBlockSize = JceEncryptionConstants.SYMMETRIC_CIPHER_BLOCK_SIZE;
        long offset = leftmostBytePosition % cipherBlockSize;
        long lowerBound = leftmostBytePosition - offset - cipherBlockSize;
        if (lowerBound < 0) {
            return 0;
        } else {
            return lowerBound;
        }
    }

    /**
     * Takes the position of the rightmost desired byte of a user specified range and returns the
     * position of the end of the following cipher block.
     */
    private static long getCipherBlockUpperBound(long rightmostBytePosition) {
        long cipherBlockSize = JceEncryptionConstants.SYMMETRIC_CIPHER_BLOCK_SIZE;
        long offset = cipherBlockSize - (rightmostBytePosition % cipherBlockSize);
        return rightmostBytePosition + offset + cipherBlockSize;
    }
}
