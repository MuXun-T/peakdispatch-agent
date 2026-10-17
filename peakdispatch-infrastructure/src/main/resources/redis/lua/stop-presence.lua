local generation = tonumber(ARGV[1])
local current = tonumber(redis.call('HGET', KEYS[1], 'generation') or '-1')
if current > generation then return -1 end
if current == generation then
    if redis.call('HGET', KEYS[1], 'online') == '0' then return 2 end
    return -2
end
redis.call('HSET', KEYS[1], 'online', '0', 'generation', generation, 'sequence', '0')
redis.call('HDEL', KEYS[1], 'region', 'latitude', 'longitude', 'heartbeatMs')
redis.call('PERSIST', KEYS[1])
return 1
