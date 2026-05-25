#!/bin/sh
#ident "%W%"
# GAI Feed SFTP transfer script
# Usage: sftp.sh <SRC_FILE> <DEST_PATH> <USER> <REMOTE_HOST>
# Bundled with scef-war — extracted to temp dir at runtime by GAISftpTransferService

SRC=$1
DEST=$2
USER=$3
REMOTE_HOST=$4

if [ ! -f $SRC ]
then
        echo "$SRC is not a file, exit"
        exit -1
fi

SRC_FILE_NAME=`echo $SRC | awk -F/ '{print $NF}'`
SFTP_BATCH=''
TAR_FILE=''

exitVal=0
index=`echo $DEST |cut -d/ -f2- | awk -F/ '{print NF}'`
echo "NUMBER OF FIELD: $index"
TRANSFORMED_PATH=`echo $DEST |cut -d/ -f2- | tr '/' ' '`
echo "TRANSFORMED_PATH=$TRANSFORMED_PATH"

for s in ${TRANSFORMED_PATH[@]}
do
        echo $s
done

echo "SRC=$SRC"
echo "DEST=$DEST"

SFTP_BATCH=`echo $SRC_FILE_NAME |tr '.' '_'`_batch
echo "$SRC is regular file"

function sftpFile(){
        ssh ${USER}@${REMOTE_HOST} mkdir -p "$DEST"
        echo "cd $DEST">>$SFTP_BATCH
        FILE_TO_SEND=$SRC
        echo "put $FILE_TO_SEND">>$SFTP_BATCH
        echo "************************************"
        echo `cat $SFTP_BATCH`
        echo "************************************"
        (/bin/sftp -B $SFTP_BATCH ${USER}@${REMOTE_HOST}) > /tmp/sftp.log 2>&1
        exitVal=$?

        if [ "$exitVal" -ne "0" ]
        then
                echo "sftp -B fail, try sftp -b"
                (/bin/sftp -b $SFTP_BATCH ${USER}@${REMOTE_HOST}) > /tmp/sftp.log 2>&1
                exitVal=$?
        fi

        if [ -e ${FILE_TO_SEND}_tmp ]
        then
                        rm ${FILE_TO_SEND}_tmp
        fi
}

function main(){
        if [ "$exitVal" -eq "0" ]
        then
                sftpFile
        else
                echo "Error Compressing file(s). Exit code: $exitVal"
        fi

        if [ -e "$SFTP_BATCH" ]
        then
                rm $SFTP_BATCH
        fi

        if [ "$exitVal" -eq "0" ]
        then
                echo "Sftp for file(s) $SRC Successful!"
        else
                echo "Sftp for file(s) $SRC Failed!"
        fi

        exit $exitVal
}

main
