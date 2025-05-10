package com.example.startapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    TextView txtString;
    public String BASE_URL = "http://192.168.1.3:5000";
    private static final int PERMISSION_REQUEST_CODE = 123;
    ListView listView;
    ArrayList<String> uploadedFilesList;
    ArrayList<String> dirsToUpload;
    ArrayAdapter arrayAdapter;
    OkHttpClient client = new OkHttpClient();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i("startApp", "created");
        super.onCreate(savedInstanceState);
        requestPermissions();
        setContentView(R.layout.activity_main);
        Button clearCacheBtn = findViewById(R.id.clearCacheBtn);
        Button takeBackupBtn = findViewById(R.id.takeBackupBtn);
        Button selectorBtn = findViewById(R.id.selectorBtn);
        listView = findViewById(R.id.lstview);
        uploadedFilesList = new ArrayList<>();
        dirsToUpload = new ArrayList<>();
        arrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, uploadedFilesList);
        listView.setAdapter(arrayAdapter);
        selectorBtn.setOnClickListener(v -> openSelector());
        takeBackupBtn.setOnClickListener(v -> startBackup());
        clearCacheBtn.setOnClickListener(v -> cleanCacheData());
    }

    private void openSelector() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addCategory(Intent.CATEGORY_DEFAULT);
        startActivityForResult(Intent.createChooser(i, "Choose directory"), 9999);
    }

    private void cleanCacheData() {
        Log.i("startApp", "cleaning cache data");
        clearApplicationData();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9999) {
            assert data != null;
            String path = Objects.requireNonNull(data.getData()).getPath();
            assert path != null;
            String updatedPath = path.replace("/tree/primary:", "/storage/self/primary/");
            Log.i("startApp", "adding updatedPath " + updatedPath);
            dirsToUpload.add(updatedPath);
        }
    }

    public void uploadFile(File file) {
        String url = BASE_URL + "/upload";
        RequestBody requestBody = RequestBody.create(MediaType.parse("application/zip"), file);
        MultipartBody.Builder builder = new MultipartBody.Builder();
        builder.setType(MultipartBody.FORM);
        builder.addFormDataPart("file", file.getName(), requestBody);
        MultipartBody multipartBody = builder.build();
        Request request = new Request.Builder()
                .url(url)
                .post(multipartBody)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("startApp", "onFailure: " + e.getMessage());
                call.cancel();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                assert response.body() != null;
                final String myResponse = response.body().string();
                Log.i("startApp", "got response from backend: " + response.message());
                runOnUiThread(() -> {
                    uploadedFilesList.add(myResponse);
                    arrayAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    public static long getDirectorySize(File directory) {
        long size = 0;
        if (directory.isFile())
            return directory.length();
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getDirectorySize(file); // Recursive call for subdirectories
                }
            }
        }
        return size;
    }

    public void startBackup() {
        dirsToUpload.stream().map(File::new).forEach(this::takeBackup);
    }

    public void takeBackup(File file) {
        PublicKey publicKey;
        SecretKey aesKey;
        File encryptedKeyFile;
        if (getDirectorySize(file) > 2000000) {
            Log.e("startApp", "can not take backup as directory size bigger then 2 MB");
            uploadedFilesList.add("directory size bigger then 2 MB");
            arrayAdapter.notifyDataSetChanged();
            return;
        }
        try {
            publicKey = getPublicKey();
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            Log.e("startApp", "Failed to get public key due to exception: " + Objects.requireNonNull(e.getMessage()));
            throw new RuntimeException(e);
        }
        try {
            aesKey = getAes();
        } catch (NoSuchAlgorithmException e) {
            Log.e("startApp", "Failed to get aes key due to exception: " + Objects.requireNonNull(e.getMessage()));
            throw new RuntimeException(e);
        }
        try {
            encryptedKeyFile = getEncryptedAes(publicKey, aesKey);
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException |
                 IllegalBlockSizeException | BadPaddingException | IOException e) {
            Log.e("startApp", "Failed to encrypt aes key due to exception: " + Objects.requireNonNull(e.getMessage()));
            throw new RuntimeException(e);
        }
        // upload aes key file
        uploadFile(encryptedKeyFile);
        Log.i("startApp", "successfully uploaded file: " + encryptedKeyFile.getName());
        File backTarFile = null;
        try {
            backTarFile = createTarGz(file.getAbsolutePath());
            Log.i("startApp", "successfully created: " + backTarFile.getName());
        } catch (IOException e) {
            Log.i("startApp", "failed to create temp tar file with exception: " + e.getMessage());
        }
        if (backTarFile == null) {
            Log.e("startApp", "Failed to take backup as tar file could not be created");
            return;
        }
        File encryptedFile = null;
        try {
            encryptedFile = encryptData(aesKey, backTarFile);
            Log.i("startApp", "successfully encrypted file: " + backTarFile.getName());
        } catch (IOException | IllegalBlockSizeException | BadPaddingException |
                 NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            Log.e("startApp", "failed to encrypted file: " + backTarFile.getName() + " with exception: " + e.getMessage());
        }
        if (encryptedFile == null) {
            Log.e("startApp", "Failed to take backup as tar file could not be encrypted");
            return;
        }
        uploadFile(encryptedFile);
        Log.i("startApp", "successfully uploaded file: " + encryptedFile.getName());
    }

    private File createTarGz(String sourceDirectory) throws IOException {
        File backTarFile = File.createTempFile("backup", ".tar.gz", this.getCacheDir());
        FileOutputStream fileOutputStream = new FileOutputStream(backTarFile);
        GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(fileOutputStream);
        TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut);
        File directory = new File(sourceDirectory);
        addFileToTarGz(tarOut, directory, "");
        tarOut.close();
        return backTarFile;
    }

    private void addFileToTarGz(TarArchiveOutputStream tarOut, File file, String parentPath) throws IOException {
        Log.i("startApp", "adding file to tar: " + file.getAbsolutePath());
        String entryName = parentPath + file.getName();
        TarArchiveEntry tarEntry = new TarArchiveEntry(file, entryName);
        tarOut.putArchiveEntry(tarEntry);

        if (file.isFile()) {
            FileInputStream fileInputStream = new FileInputStream(file);
            IOUtils.copy(fileInputStream, tarOut);
            fileInputStream.close();
            tarOut.closeArchiveEntry();
        } else if (file.isDirectory()) {
            tarOut.closeArchiveEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addFileToTarGz(tarOut, child, entryName + "/");
                }
            }
        }
    }

    private File getEncryptedAes(PublicKey publicKey, SecretKey aesKey) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException, IOException {
        // Encrypt AES key with RSA
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());
        File encryptedFile = File.createTempFile("aes_key", ".dat", this.getCacheDir());
        FileOutputStream fosKey = new FileOutputStream(encryptedFile);
        fosKey.write(encryptedAesKey);
        fosKey.close();
        return encryptedFile;
    }

    private File encryptData(SecretKey aesKey, File inputFile) throws IOException, IllegalBlockSizeException, BadPaddingException, NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException {
        Cipher aesCipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey);
        byte[] inputBytes = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            inputBytes = Files.readAllBytes(inputFile.toPath());
        }
        byte[] encryptedBytes = aesCipher.doFinal(inputBytes);
        File encryptedFile = File.createTempFile(inputFile.getName(), ".enc", this.getCacheDir());
        FileOutputStream fosData = new FileOutputStream(encryptedFile);
        fosData.write(encryptedBytes);
        fosData.close();
        return encryptedFile;
    }

    private PublicKey getPublicKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String keyContent = null;
        try (InputStream inputStream = getResources().openRawResource(R.raw.start_app_key_pub)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                keyContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (keyContent == null) {
            Log.e("startApp", "public key not present");
            throw new IOException();
        }
        String replacedKey = keyContent.replace("-----BEGIN PUBLIC KEY-----", "")
                .replaceAll("\\n", "")
                .replace("-----END PUBLIC KEY-----", "");
        byte[] publicKeyBytes = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            publicKeyBytes = Base64.getDecoder().decode(replacedKey);
        }
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(keySpec);
    }

    private SecretKey getAes() throws NoSuchAlgorithmException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        return keyGenerator.generateKey();
    }

    public void clearApplicationData() {
        File cacheDirectory = this.getCacheDir();
        File applicationDirectory = new File(Objects.requireNonNull(cacheDirectory.getParent()));
        if (applicationDirectory.exists()) {
            String[] fileNames = applicationDirectory.list();
            assert fileNames != null;
            for (String fileName : fileNames) {
                if (!fileName.equals("lib")) {
                    deleteFile(new File(applicationDirectory, fileName));
                }
            }
        }
    }

    public static boolean deleteFile(File file) {
        Log.i("startApp", "deleting file/dir: " + file.getAbsolutePath());
        boolean deletedAll = true;
        if (file.isDirectory()) {
            String[] children = file.list();
            for (int i = 0; i < Objects.requireNonNull(children).length; i++) {
                deletedAll = deleteFile(new File(file, children[i])) && deletedAll;
            }
        } else {
            deletedAll = file.delete();
        }

        return deletedAll;
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.INTERNET,
                Manifest.permission.READ_MEDIA_IMAGES
        };
        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]), // Convert list to array
                    PERMISSION_REQUEST_CODE
            );
        } else {
            Toast.makeText(this, "All permissions already granted", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            List<String> deniedPermissions = new ArrayList<>();
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i]);
                }
            }
            if (deniedPermissions.isEmpty()) {
                // All permissions granted
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
            } else {
                // Some permissions were denied, show them in a Toast
                Toast.makeText(this, "Permissions denied: " + deniedPermissions, Toast.LENGTH_LONG).show();
            }
        }
    }
}