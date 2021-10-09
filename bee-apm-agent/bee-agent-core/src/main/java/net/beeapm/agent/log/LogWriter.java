package net.beeapm.agent.log;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import net.beeapm.agent.common.BeeAgentJarUtils;
import net.beeapm.agent.common.BeeUtils;
import net.beeapm.agent.config.ConfigUtils;
import org.apache.commons.lang3.time.DateFormatUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;


public class LogWriter implements EventHandler<LogMessage> {
    private static final char[] lock = new char[1];
    private static LogWriter logWriter;
    private Disruptor<LogMessage> disruptor;
    private RingBuffer<LogMessage> buffer;
    private FileOutputStream fileOutputStream;
    private static List<String> logFileList;
    private volatile long fileSize;
    private volatile int lineNum;
    private static long MAX_FILE_SIZE;
    private static int MAX_FILE_NUM;
    private static String logDir;

    public static LogWriter me() {
        if (logWriter == null) {
            synchronized (lock) {
                if (logWriter == null) {
                    logWriter = new LogWriter();
                }
            }
        }
        return logWriter;
    }

    private LogWriter() {
        MAX_FILE_SIZE = ConfigUtils.me().getLong("logger.fileSize",10485760L);
        MAX_FILE_NUM = ConfigUtils.me().getInt("logger.fileNum",5);
        logDir = BeeAgentJarUtils.getAgentJarDirPath() + "/logs";
        logFileList = new ArrayList<String>(MAX_FILE_NUM + 1);
        File logDirFile = new File(logDir);
        if(!logDirFile.exists()){
            logDirFile.mkdirs();
        }else {
            File[] files = logDirFile.listFiles();
            for(int i = 0; i < files.length; i++){
                if(files[i].isFile() && files[i].getName().length() > 7){
                    logFileList.add(files[i].getName());
                }
            }
            Collections.sort(logFileList);
            deleteFile();
        }

        disruptor = new Disruptor<LogMessage>(new EventFactory<LogMessage>() {
            @Override
            public LogMessage newInstance() {
                return new LogMessage();
            }
        }, 1024, LogThreadFactory.INSTANCE);
        disruptor.handleEventsWith(this);
        buffer = disruptor.getRingBuffer();
        lineNum = 0;
        disruptor.start();
    }

    @Override
    public void onEvent(LogMessage event, long sequence, boolean endOfBatch){
        if (canWrite()) {
            try {
                write(event.getMessage() + "\n", endOfBatch);
            } finally {
                event.setMessage(null);
            }
        }
    }

    private void write(String message, boolean forceFlush) {
        try {
            fileOutputStream.write(message.getBytes("utf-8"));
            fileSize += message.getBytes("utf-8").length;
            lineNum++;
            if (forceFlush || lineNum % 10 == 0) {
                fileOutputStream.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            switchFile();
        }
    }

    private void switchFile() {
        if (fileSize > MAX_FILE_SIZE) {
            BeeUtils.flush(fileOutputStream);
            BeeUtils.close(fileOutputStream);
            File logFile = new File(logDir,"bee.log");
            if(logFile.exists()){
                String newName = "bee-" + DateFormatUtils.format(new Date(),"yyyyMMddHHmmss")+".log";
                logFile.renameTo(new File(logDir,newName));
                logFileList.add(newName);
                deleteFile();
            }
            fileOutputStream = null;
        }
    }

    private void deleteFile(){
        int listSize = logFileList.size();
        if(listSize > MAX_FILE_NUM){
            for(int i = 0; i < listSize - MAX_FILE_NUM; i++){
                File delFile = new File(logDir,logFileList.get(0));
                if(delFile.exists()) {
                    delFile.delete();
                }
                logFileList.remove(0);
            }
        }
    }

    private boolean canWrite() {
        if (fileOutputStream != null) {
            return true;
        }
        try {
            File logFile = new File(logDir, "bee.log");
            if(!logFile.exists()){
                logFile.createNewFile();
            }
            fileOutputStream = new FileOutputStream(logFile, true);
            fileSize = logFile.length();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return fileOutputStream != null;
    }

    public void writeLog(String message) {
        long next = buffer.next();
        try {
            LogMessage logMessage = buffer.get(next);
            logMessage.setMessage(message);
        } finally {
            buffer.publish(next);
        }
    }
}
