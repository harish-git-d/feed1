package com.citi.risk.scef.limitexposure.gai.domain;

/** Metadata about one generated GAI file — used to build the .ctrl trigger file. */
public class FileMetadata {

    private final String fileName;
    private final String filePath;
    private final int recordCount;
    private final String fileType;

    public FileMetadata(String fileName, String filePath, int recordCount, String fileType) {
        this.fileName    = fileName;
        this.filePath    = filePath;
        this.recordCount = recordCount;
        this.fileType    = fileType;
    }

    public String getFileName()    { return fileName; }
    public String getFilePath()    { return filePath; }
    public int    getRecordCount() { return recordCount; }
    public String getFileType()    { return fileType; }
}
