local existing = redis.call('HGET', KEYS[1], ARGV[1])
if not existing then return 0 end
local token = string.match(existing, '^([^|]+)|')
if token ~= ARGV[2] then return -1 end
redis.call('HDEL', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
