-- Drop the existing function first
DROP FUNCTION IF EXISTS clear_and_verify_encrypted_token(TEXT, TEXT, TEXT);

-- Recreate with proper security settings and search_path
CREATE OR REPLACE FUNCTION clear_and_verify_encrypted_token(
  p_user_id TEXT,
  p_key_name TEXT,
  p_encryption_key TEXT
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, extensions
AS $$
DECLARE
  v_old_encrypted BYTEA;
  v_new_encrypted BYTEA;
  v_old_decrypted TEXT;
  v_new_decrypted TEXT;
  v_old_json JSONB;
  v_new_json JSONB;
  v_update_count INT;
BEGIN
  -- Get the current encrypted preferences
  SELECT encrypted_preferences INTO v_old_encrypted
  FROM user_preferences
  WHERE user_id = p_user_id
  FOR UPDATE; -- Lock the row for update
  
  IF v_old_encrypted IS NULL THEN
    RETURN json_build_object(
      'success', false,
      'token_cleared', false,
      'message', 'No encrypted preferences found',
      'old_value_existed', false
    );
  END IF;
  
  -- Decrypt the current preferences
  BEGIN
    v_old_decrypted := pgp_sym_decrypt(v_old_encrypted, p_encryption_key);
    v_old_json := v_old_decrypted::jsonb;
  EXCEPTION WHEN OTHERS THEN
    RETURN json_build_object(
      'success', false,
      'token_cleared', false,
      'message', 'Error decrypting preferences: ' || SQLERRM,
      'old_value_existed', false
    );
  END;
  
  -- Check if the key exists
  IF NOT (v_old_json ? p_key_name) THEN
    RETURN json_build_object(
      'success', true,
      'token_cleared', true,
      'message', 'Key was already cleared',
      'old_value_existed', false
    );
  END IF;
  
  -- Remove the key from the JSON
  v_new_json := v_old_json - p_key_name;
  
  -- Encrypt the new preferences
  v_new_encrypted := pgp_sym_encrypt(v_new_json::text, p_encryption_key);
  
  -- Update the encrypted preferences
  UPDATE user_preferences
  SET encrypted_preferences = v_new_encrypted
  WHERE user_id = p_user_id;
  
  GET DIAGNOSTICS v_update_count = ROW_COUNT;
  
  IF v_update_count = 0 THEN
    RETURN json_build_object(
      'success', false,
      'token_cleared', false,
      'message', 'Failed to update preferences',
      'old_value_existed', true
    );
  END IF;
  
  -- Verify the key was removed by reading it back
  SELECT encrypted_preferences INTO v_new_encrypted
  FROM user_preferences
  WHERE user_id = p_user_id;
  
  v_new_decrypted := pgp_sym_decrypt(v_new_encrypted, p_encryption_key);
  v_new_json := v_new_decrypted::jsonb;
  
  -- Final verification
  IF v_new_json ? p_key_name THEN
    RETURN json_build_object(
      'success', false,
      'token_cleared', false,
      'message', 'Key still exists after removal attempt',
      'old_value_existed', true
    );
  ELSE
    RETURN json_build_object(
      'success', true,
      'token_cleared', true,
      'message', 'Successfully cleared and verified',
      'old_value_existed', true
    );
  END IF;
  
EXCEPTION WHEN OTHERS THEN
  RETURN json_build_object(
    'success', false,
    'token_cleared', false,
    'message', 'Error: ' || SQLERRM,
    'old_value_existed', false
  );
END;
$$;

-- Grant execute permission
GRANT EXECUTE ON FUNCTION clear_and_verify_encrypted_token(TEXT, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION clear_and_verify_encrypted_token(TEXT, TEXT, TEXT) TO anon;