local token = redis.call('HGET', KEYS[4], ARGV[1])
if not token then return 0 end
if token ~= ARGV[2] then return -1 end
redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('HDEL', KEYS[4], ARGV[1])
return 1
